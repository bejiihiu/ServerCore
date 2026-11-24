package kz.bejiihiu.servercore.guest

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import kz.bejiihiu.servercore.ServerCorePlugin
import kz.bejiihiu.servercore.integration.flectonepulse.PunishmentKind
import kz.bejiihiu.servercore.integration.flectonepulse.PunishmentQuery
import kz.bejiihiu.servercore.ws.BackendGateway
import kz.bejiihiu.servercore.ws.GuestEndedPayload
import kz.bejiihiu.servercore.ws.GuestStartedPayload
import kz.bejiihiu.servercore.ws.MessageTypes
import kz.bejiihiu.servercore.ws.SnapshotPayload
import kz.bejiihiu.servercore.ws.messageEnvelope
import kz.bejiihiu.servercore.ws.newSessionId
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player

/** the snapshot as it travels over the wire, field names are the protocol contract */
fun PlayerSnapshot.toPayload(): SnapshotPayload = SnapshotPayload(
    world = world, x = x, y = y, z = z,
    yaw = yaw, pitch = pitch,
    gameMode = gameMode, flying = flying,
    flySpeed = flySpeed, walkSpeed = walkSpeed,
)

/**
 * owns the guest session lifecycle:
 *
 * join -> restrictions on immediately (provisional) -> account check
 *   - guest  -> capture or restore snapshot once, spectator, guest_started, welcome
 *   - active -> restrictions off, snapshot untouched
 *   - denied -> kick
 *
 * rejoin without auth -> the stored snapshot is restored before anything else,
 * the original position is never replaced by a spectator wandering point.
 * authenticated -> snapshot completed, speeds and mode restored, no position
 * restore, the player keeps exploring from wherever they authenticated.
 *
 * races this service is built around:
 *   - duplicate login (same uuid, two clients): last join wins, the session
 *     continues for the new client, the stale instance's quit is recognised
 *     and ignored, the budget burns exactly once
 *   - telegram approval landing while a guest session is still starting:
 *     the approval latch makes the ungate win and no budget is burned
 *   - a late stale resolution arriving after approval: ungated, never re-guested
 *
 * every player mutation goes through the entity scheduler, no main thread
 * assumptions anywhere. file io lives on virtual threads.
 */
class GuestSessionService(
    private val plugin: ServerCorePlugin,
    private val config: GuestConfig,
    private val accounts: AccountStateResolver,
    private val linkSource: GuestLinkSource,
    private val snapshots: SnapshotStore,
    private val counter: GuestSessionCounter,
    private val gateway: BackendGateway,
    private val messages: GuestMessageService,
    /**
     * read-only flectonepulse punishment lookup, resolved lazily so the guest
     * flow works with and without the fp bridge connected
     */
    private val punishmentQuery: () -> PunishmentQuery = { PunishmentQuery.None },
) {

    private val registry = GuestSessionRegistry()
    private val provisional = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingRestores = ConcurrentHashMap<UUID, StoredSnapshot>()
    private val pendingResolves = ConcurrentHashMap<UUID, CompletableFuture<AccountStatus>>()
    private val modeTransitions = ConcurrentHashMap.newKeySet<UUID>()
    private val reminderTasks = ConcurrentHashMap<UUID, ScheduledTask>()
    private val sweepTasks = ConcurrentHashMap<UUID, ScheduledTask>()
    private val lastSweepFix = ConcurrentHashMap<UUID, Long>()

    /** which deep-link url this client instance was already shown, per session */
    private val shownLinks = ConcurrentHashMap<UUID, String>()

    /** a guest is anyone restricted right now: confirmed session or pending check */
    fun isRestricted(uuid: UUID): Boolean = config.enabled && (uuid in provisional || registry.contains(uuid))

    /** true while the plugin itself is switching the mode, lets the guard listener pass it */
    fun isInModeTransition(uuid: UUID): Boolean = uuid in modeTransitions

    /** restart recovery: everything durable becomes a pending restore */
    fun markPendingRestores(stored: Map<UUID, StoredSnapshot>) {
        if (!config.restoreOnRestart || stored.isEmpty()) return
        pendingRestores.putAll(stored)
        plugin.logger.info("queued ${stored.size} guest snapshot(s) for restore on next join")
    }

    fun onJoin(player: Player) {
        val uuid = player.uniqueId
        if (config.enabled) provisional.add(uuid)

        // duplicate login: a session already runs for this uuid, the new client
        // takes it over (last join wins), the stale instance is on its way out
        val continuing = registry.contains(uuid)
        onPlayerThread(player, retire = { provisional.remove(uuid) }) {
            if (player.isOnline && continuing && registry.contains(uuid)) rebindSession(player)
        }
        requestResolution(player)
    }

    fun onQuit(player: Player) {
        val uuid = player.uniqueId
        // duplicate login teardown: when the uuid already maps to a different
        // live player, this quit belongs to the replaced stale instance and
        // must not tear down the session the new client just took over
        val live = runCatching { Bukkit.getPlayer(uuid) }.getOrNull()
        if (live != null && live !== player) return

        pendingResolves.remove(uuid)?.cancel(true)
        provisional.remove(uuid)
        modeTransitions.remove(uuid)
        registry.clearAuthorization(uuid)
        cancelLoops(uuid)
        lastSweepFix.remove(uuid)
        shownLinks.remove(uuid)
        registry.remove(uuid) {
            // re-checked under the start mutex: between the cheap identity
            // check above and this removal a same-uuid rejoin may have
            // slipped in, and its fresh session must survive
            val nowLive = runCatching { Bukkit.getPlayer(uuid) }.getOrNull()
            nowLive == null || nowLive === player
        }?.let { session ->
            // session ended by leaving, the snapshot stays durable for the next join
            sendEnvelope(
                messageEnvelope(
                    MessageTypes.GUEST_ENDED,
                    GuestEndedPayload(uuid.toString(), session.sessionId, "LOGOUT"),
                ),
            )
        }
        // no PLAYER_LEFT here: the presence layer owns the join/left announcements
        // (a second copy would make the backend close+cancel its session twice)
    }

    /** called from the ws resolver when the backend confirms a login, any thread */
    fun onAuthApproved(uuid: UUID) {
        // latch + budget reset under the start mutex: approval beats an
        // in-flight session start and no increment can land after the reset
        registry.approve(uuid) { counter.reset(uuid) }
        val player = Bukkit.getPlayer(uuid) ?: run {
            // the player is already gone: the latch must not outlive the
            // session, otherwise the next join of the same (nickname-derived)
            // uuid walks in pre-approved without a fresh backend verdict
            registry.clearAuthorization(uuid)
            return
        }
        onPlayerThread(player) { ungateAuthenticated(player) }
    }

    /** called from the ws resolver when a personal deep link shows up, any thread */
    fun onLinkAvailable(uuid: UUID) {
        // the prompt may already carry this exact link, only resend on an actual upgrade
        val link = linkSource.pendingLink(uuid)?.url ?: return
        if (shownLinks[uuid] == link) return
        if (!registry.contains(uuid)) return
        val player = Bukkit.getPlayer(uuid) ?: return
        onPlayerThread(player) {
            if (registry.contains(uuid) && player.isOnline) showPrompt(player, link)
        }
    }

    /** re-announce live sessions after the ws link comes back so both sides agree on reality */
    fun reconcile() {
        registry.all().forEach { (uuid, session) ->
            sendGuestStarted(uuid, session.username, session.sessionId, session.snapshot)
        }
    }

    /** remembers what was shown so the deep-link prompt goes out once per session */
    private fun showPrompt(player: Player, personalUrl: String?) {
        val url = personalUrl ?: linkSource.pendingLink(player.uniqueId)?.url ?: config.fallbackBotUrl
        if (shownLinks[player.uniqueId] == url) return
        shownLinks[player.uniqueId] = url
        messages.sendPrompt(player, url)
    }

    /** past the session budget: rich register-now screen, session not started */
    fun kickOverLimit(player: Player) {
        provisional.remove(player.uniqueId)
        onPlayerThread(player) {
            player.kick(messages.limitKickMessage())
        }
    }

    private fun requestResolution(player: Player) {
        val uuid = player.uniqueId
        val future = accounts.resolve(uuid, player.name)
        // registered before the callback: a synchronous resolver (local
        // fallback) completes the future before whenComplete is attached,
        // and the map entry must not outlive the resolution
        pendingResolves[uuid] = future
        future.whenComplete { status, _ ->
            pendingResolves.remove(uuid, future)
            // any failure (timeout, bad frame, dead link) fails closed to guest
            val resolved = status ?: AccountStatus.GuestPendingLogin(null)
            onPlayerThread(player, retire = { provisional.remove(uuid) }) {
                applyResolution(player, resolved)
            }
        }
    }

    private fun applyResolution(player: Player, status: AccountStatus) {
        val uuid = player.uniqueId
        if (!player.isOnline) return
        val link = (status as? AccountStatus.GuestPendingLogin)?.link
        // the stored snapshot (restart recovery or a prior unauthenticated
        // leave) always beats a fresh capture, the original position survives
        val stored = pendingRestores.remove(uuid) ?: snapshots.pending(uuid)
        when (val action = JoinPolicy.decide(
            status = status,
            sessionExists = registry.contains(uuid),
            alreadyAuthorized = registry.isAuthorized(uuid),
            sessionsUsed = counter.count(uuid),
            sessionLimit = config.sessionLimit,
            kickAfterLimit = config.kickAfterLimit,
            storedSnapshot = stored != null,
            guestEnabled = config.enabled,
        )) {
            is JoinAction.Ungate -> ungateAuthenticated(player)
            is JoinAction.KeepExisting -> showPrompt(player, link)
            is JoinAction.KickOverLimit -> kickOverLimit(player)
            is JoinAction.KickDisabled -> {
                // guest mode off: unauthenticated players simply do not get in
                provisional.remove(uuid)
                player.kick(messages.guestDisabledMessage())
            }
            is JoinAction.KickDenied -> {
                val session = registry.remove(uuid)
                session?.let {
                    sendEnvelope(
                        messageEnvelope(
                            MessageTypes.GUEST_ENDED,
                            GuestEndedPayload(uuid.toString(), it.sessionId, "KICK"),
                        ),
                    )
                }
                if (session != null || snapshots.pending(uuid) != null) snapshots.complete(uuid)
                provisional.remove(uuid)
                cancelLoops(uuid)
                player.kick(messages.kickMessage())
            }
            is JoinAction.StartGuest -> {
                val session = registry.start(uuid, create = {
                    val created = startSessionState(player, uuid, if (action.restoreStored) stored else null)
                    // one burn per started session, inside the start mutex so a
                    // concurrent approval reset can never interleave with it
                    counter.increment(uuid)
                    created
                })
                if (session == null) {
                    // a duplicate join or a late approval got there first
                    if (registry.isAuthorized(uuid)) ungateAuthenticated(player) else showPrompt(player, link)
                    return
                }
                sendGuestStarted(uuid, player.name, session.sessionId, session.snapshot)
                beginRichFlow(player, uuid, restored = action.restoreStored, overLimit = action.overLimit, link = link)
            }
        }
    }

    /**
     * the bukkit half of starting a session, runs under the registry start
     * mutex on the player's entity thread. [stored] null means fresh capture,
     * otherwise the original snapshot is reused and rebound to a new session
     * id — a rejoin never overwrites the captured location with a spectator
     * wandering point.
     */
    private fun startSessionState(player: Player, uuid: UUID, stored: StoredSnapshot?): GuestSession {
        val snapshot = if (stored != null) {
            stored.snapshot.toLocation(Bukkit.getWorld(stored.snapshot.world))?.let { player.teleportAsync(it) }
            stored.snapshot
        } else {
            captureSnapshot(player)
        }
        enterSpectator(player)
        val sessionId = newSessionId()
        if (stored != null) {
            snapshots.updateSessionId(uuid, sessionId)
        } else {
            snapshots.save(uuid, StoredSnapshot(sessionId, System.currentTimeMillis(), snapshot))
        }
        return GuestSession(sessionId, player.name, snapshot)
    }

    /** duplicate login: same session continues for the new client, no new burn */
    private fun rebindSession(player: Player) {
        val uuid = player.uniqueId
        if (!registry.contains(uuid)) return
        enterSpectator(player)
        // the new client has not seen any prompt yet, it gets exactly one
        shownLinks.remove(uuid)
        beginRichFlow(player, uuid, restored = true, overLimit = counter.count(uuid) >= config.sessionLimit, link = null)
    }

    /** welcome title + chat prompt + the periodic reminder and gamemode sweep loops */
    private fun beginRichFlow(player: Player, uuid: UUID, restored: Boolean, overLimit: Boolean, link: String?) {
        messages.sendWelcome(player, restored, counter.count(uuid), config.sessionLimit)
        showPrompt(player, link)
        if (overLimit) {
            messages.sendLimitWarning(player)
        } else if (counter.count(uuid) >= config.sessionLimit - 1) {
            // warn one session early: the wall itself is too late to act on
            messages.sendLastSessionNotice(player)
        }
        scheduleReminder(uuid, player)
        scheduleModeSweep(uuid, player)
        notifyIfMuted(uuid, player)
    }

    /**
     * flectonepulse integration: if the player carries an active mute, one
     * line explains why chat stays silent. the lookup may hit fp's storage,
     * so it runs on the async scheduler and the notice lands via the entity
     * scheduler like every other player mutation.
     */
    private fun notifyIfMuted(uuid: UUID, player: Player) {
        plugin.server.asyncScheduler.runNow(plugin, Consumer<ScheduledTask> { _ ->
            val muted = punishmentQuery().hasActive(PunishmentKind.MUTE, uuid)
            if (muted) {
                onPlayerThread(player) {
                    if (player.isOnline && registry.contains(uuid)) messages.sendMuteNotice(player)
                }
            }
        })
    }

    private fun scheduleReminder(uuid: UUID, player: Player) {
        val periodTicks = config.reminderIntervalSeconds * 20
        // identity holder: the retire hook must only ever drop its own task,
        // a duplicate-login rebind replaces the map entry right after
        val holder = java.util.concurrent.atomic.AtomicReference<ScheduledTask>()
        // folia's entity scheduler hands back null once the entity is retiring
        val task = player.scheduler.runAtFixedRate(
            plugin,
            Consumer<ScheduledTask> {
                // player may have left without a quit event racing us; the retire
                // hook below covers the rest
                if (player.isOnline && registry.contains(uuid)) messages.sendReminder(player)
            },
            Runnable { holder.get()?.let { current -> reminderTasks.remove(uuid, current) } },
            periodTicks,
            periodTicks,
        ) ?: return
        holder.set(task)
        reminderTasks.put(uuid, task)?.cancel()
    }

    /**
     * gamemode drift sweep: another plugin (or anything that ignores the
     * cancel event) can move a guest out of spectator. the check is cheap and
     * runs on the player's own entity scheduler — a region thread by
     * definition — and actual corrections are throttled so a stubborn plugin
     * does not turn this into a per-tick fight.
     */
    private fun scheduleModeSweep(uuid: UUID, player: Player) {
        val periodTicks = config.gamemodeSweepSeconds * 20
        val holder = java.util.concurrent.atomic.AtomicReference<ScheduledTask>()
        val task = player.scheduler.runAtFixedRate(
            plugin,
            Consumer<ScheduledTask> {
                if (!player.isOnline || !registry.contains(uuid)) return@Consumer
                if (uuid in modeTransitions) return@Consumer
                if (player.gameMode == GameMode.SPECTATOR) return@Consumer
                val now = System.currentTimeMillis()
                if (now - (lastSweepFix[uuid] ?: 0) < SWEEP_THROTTLE_MS) return@Consumer
                lastSweepFix[uuid] = now
                enterSpectator(player)
            },
            Runnable { holder.get()?.let { current -> sweepTasks.remove(uuid, current) } },
            periodTicks,
            periodTicks,
        ) ?: return
        holder.set(task)
        sweepTasks.put(uuid, task)?.cancel()
    }

    private fun cancelLoops(uuid: UUID) {
        reminderTasks.remove(uuid)?.cancel()
        sweepTasks.remove(uuid)?.cancel()
    }

    private fun enterSpectator(player: Player) {
        modeTransitions.add(player.uniqueId)
        try {
            player.gameMode = GameMode.SPECTATOR
        } finally {
            modeTransitions.remove(player.uniqueId)
        }
    }

    /** auth confirmed: back to normal play from the current position, snapshot finished */
    private fun ungateAuthenticated(player: Player) {
        val uuid = player.uniqueId
        provisional.remove(uuid)
        cancelLoops(uuid)
        lastSweepFix.remove(uuid)
        shownLinks.remove(uuid)
        pendingRestores.remove(uuid)
        val session = registry.remove(uuid)
        // the approval latch stays set until quit so any stale resolution that
        // still arrives ungates instead of re-guesting the player
        // drop the durable snapshot only when there actually is one, an
        // authorized straight join must not touch the snapshot file at all
        if (session != null || snapshots.pending(uuid) != null) snapshots.complete(uuid)
        if (session == null) return
        modeTransitions.add(uuid)
        try {
            session.snapshot.applyTo(player)
        } finally {
            modeTransitions.remove(uuid)
        }
        sendEnvelope(
            messageEnvelope(
                MessageTypes.GUEST_ENDED,
                GuestEndedPayload(uuid.toString(), session.sessionId, "AUTHENTICATED"),
            ),
        )
        messages.sendApproved(player)
    }

    private fun sendGuestStarted(uuid: UUID, username: String, sessionId: String, snapshot: PlayerSnapshot) {
        sendEnvelope(
            messageEnvelope(
                MessageTypes.GUEST_STARTED,
                GuestStartedPayload(uuid.toString(), username, sessionId, snapshot.toPayload()),
            ),
        )
    }

    private fun sendEnvelope(envelope: kz.bejiihiu.servercore.ws.WsEnvelope) {
        if (!gateway.send(envelope)) {
            // the ws layer owns durable outbox replay once it lands, drops here are expected
            plugin.logger.fine("backend link down, ${envelope.type} stays local until reconnect")
        }
    }

    private fun onPlayerThread(player: Player, retire: Runnable? = null, block: () -> Unit) {
        player.scheduler.run(plugin, Consumer<ScheduledTask> { block() }, retire)
    }

    private companion object {
        /** minimum spacing between drift corrections per guest */
        const val SWEEP_THROTTLE_MS = 15_000L
    }
}
