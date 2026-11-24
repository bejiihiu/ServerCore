package kz.bejiihiu.servercore.guest

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import kz.bejiihiu.servercore.ServerCorePlugin
import kz.bejiihiu.servercore.ws.BackendGateway
import kz.bejiihiu.servercore.ws.GuestEndedPayload
import kz.bejiihiu.servercore.ws.GuestStartedPayload
import kz.bejiihiu.servercore.ws.MessageTypes
import kz.bejiihiu.servercore.ws.PlayerJoinedPayload
import kz.bejiihiu.servercore.ws.PlayerLeftPayload
import kz.bejiihiu.servercore.ws.SnapshotPayload
import kz.bejiihiu.servercore.ws.messageEnvelope
import kz.bejiihiu.servercore.ws.newSessionId
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import net.kyori.adventure.text.Component

/** the snapshot as it travels over the wire, field names are the protocol contract */
fun PlayerSnapshot.toPayload(): SnapshotPayload = SnapshotPayload(
    world = world, x = x, y = y, z = z,
    yaw = yaw, pitch = pitch,
    gameMode = gameMode, flying = flying,
    flySpeed = flySpeed, walkSpeed = walkSpeed,
)

data class GuestSession(val sessionId: String, val username: String, val snapshot: PlayerSnapshot)

/**
 * owns the guest session lifecycle:
 *
 * join -> restrictions on immediately (provisional) -> account check
 *   - guest  -> capture snapshot once, spectator, guest_started, welcome
 *   - active -> restrictions off, snapshot untouched
 *   - denied -> kick
 *
 * rejoin without auth -> stored snapshot restored before anything else, the
 * original position is never replaced by a spectator wandering point.
 * authenticated -> snapshot completed, speeds and mode restored, no position
 * restore, the player keeps exploring from wherever they authenticated.
 *
 * session budget: every join without auth burns one session from the limit;
 * past the limit the player is kicked with a register-now message (or warned,
 * when kick-after-limit is off). auth success resets the budget.
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
) {

    private val sessions = ConcurrentHashMap<UUID, GuestSession>()
    private val provisional = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingRestores = ConcurrentHashMap<UUID, StoredSnapshot>()
    private val pendingResolves = ConcurrentHashMap<UUID, CompletableFuture<AccountStatus>>()
    private val modeTransitions = ConcurrentHashMap.newKeySet<UUID>()
    private val reminderTasks = ConcurrentHashMap<UUID, ScheduledTask>()
    private val shownLinks = ConcurrentHashMap<UUID, String>()

    /** a guest is anyone restricted right now: confirmed session or pending check */
    fun isRestricted(uuid: UUID): Boolean = config.enabled && (uuid in provisional || sessions.containsKey(uuid))

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

        val stored = if (config.restoreOnRestart) pendingRestores.remove(uuid) else null
        onPlayerThread(player, retire = { provisional.remove(uuid) }) {
            if (stored != null) beginRestoredSession(player, stored)
        }
        requestResolution(player)
    }

    fun onQuit(player: Player) {
        val uuid = player.uniqueId
        pendingResolves.remove(uuid)?.cancel(true)
        provisional.remove(uuid)
        modeTransitions.remove(uuid)
        cancelReminder(uuid)
        val session = sessions.remove(uuid)
        if (session != null) {
            // session ended by leaving, the snapshot stays durable for the next join
            sendEnvelope(
                messageEnvelope(
                    MessageTypes.GUEST_ENDED,
                    GuestEndedPayload(uuid.toString(), session.sessionId, "LOGOUT"),
                ),
            )
        }
        sendEnvelope(
            messageEnvelope(MessageTypes.PLAYER_LEFT, PlayerLeftPayload(uuid.toString(), player.name)),
        )
    }

    /** called from the ws resolver when the backend confirms a login, any thread */
    fun onAuthApproved(uuid: UUID) {
        // budget starts over once the telegram binding vouches for the player
        counter.reset(uuid)
        val player = Bukkit.getPlayer(uuid) ?: return
        onPlayerThread(player) { ungateAuthenticated(player) }
    }

    /** called from the ws resolver when a personal deep link shows up, any thread */
    fun onLinkAvailable(uuid: UUID) {
        // the prompt may already carry this exact link, only resend on an actual upgrade
        val link = linkSource.pendingLink(uuid)?.url ?: return
        if (shownLinks[uuid] == link) return
        if (!sessions.containsKey(uuid)) return
        val player = Bukkit.getPlayer(uuid) ?: return
        onPlayerThread(player) {
            if (sessions.containsKey(uuid) && player.isOnline) showPrompt(player, link)
        }
    }

    /** re-announce live sessions after the ws link comes back so both sides agree on reality */
    fun reconcile() {
        sessions.forEach { (uuid, session) ->
            sendGuestStarted(uuid, session.username, session.sessionId, session.snapshot)
        }
    }

    fun resendPrompt(player: Player) {
        showPrompt(player, linkSource.pendingLink(player.uniqueId)?.url)
    }

    /** remembers what was shown so a late personal link can replace the generic one exactly once */
    private fun showPrompt(player: Player, personalUrl: String?) {
        val url = personalUrl ?: linkSource.pendingLink(player.uniqueId)?.url ?: config.fallbackBotUrl
        shownLinks[player.uniqueId] = url
        messages.sendPrompt(player, url)
    }

    /** past the session budget: rich register-now screen, session not started */
    fun kickOverLimit(player: Player) {
        provisional.remove(player.uniqueId)
        onPlayerThread(player) {
            val lines = messages.limitKickMessage()
            lines.forEach(player::sendMessage)
            player.kick(lines.lastOrNull() ?: Component.empty())
        }
    }

    private fun requestResolution(player: Player) {
        val uuid = player.uniqueId
        val future = accounts.resolve(uuid, player.name)
        pendingResolves[uuid] = future
        future.whenComplete { status, _ ->
            pendingResolves.remove(uuid)
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
        when (status) {
            is AccountStatus.Authorized -> ungateAuthenticated(player)
            is AccountStatus.GuestPendingLogin -> {
                if (!sessions.containsKey(uuid)) {
                    if (!config.enabled) {
                        // guest mode off: unauthenticated players simply do not get in
                        val lines = messages.guestDisabledMessage()
                        lines.forEach(player::sendMessage)
                        player.kick(lines.lastOrNull() ?: Component.empty())
                        return
                    }
                    if (counter.count(uuid) >= config.sessionLimit) {
                        if (config.kickAfterLimit) {
                            kickOverLimit(player)
                            return
                        }
                        // budget spent but the admin chose to let them look around
                        startGuestSession(player, captureSnapshot(player), persist = true, overLimit = true)
                        messages.sendLimitWarning(player)
                    } else {
                        startGuestSession(player, captureSnapshot(player), persist = true)
                    }
                }
                showPrompt(player, status.link)
            }
            is AccountStatus.Denied -> {
                sessions.remove(uuid)?.let { session ->
                    sendEnvelope(
                        messageEnvelope(
                            MessageTypes.GUEST_ENDED,
                            GuestEndedPayload(uuid.toString(), session.sessionId, "KICK"),
                        ),
                    )
                    snapshots.complete(uuid)
                }
                provisional.remove(uuid)
                cancelReminder(uuid)
                val lines = messages.kickMessage(status.code)
                lines.forEach(player::sendMessage)
                player.kick(lines.lastOrNull() ?: Component.empty())
            }
        }
    }

    private fun beginRestoredSession(player: Player, stored: StoredSnapshot) {
        val uuid = player.uniqueId
        if (!player.isOnline) return
        val location = stored.snapshot.toLocation(Bukkit.getWorld(stored.snapshot.world))
        if (location == null) {
            // world not loaded (yet?), keep the pending restore for a later join
            pendingRestores[uuid] = stored
            plugin.logger.warning("snapshot world '${stored.snapshot.world}' not found for $uuid, restore postponed")
        } else {
            player.teleportAsync(location)
        }
        // same original snapshot values, brand new session id: a rejoin never overwrites
        // the captured location with a spectator wandering point
        val sessionId = newSessionId()
        sessions[uuid] = GuestSession(sessionId, player.name, stored.snapshot)
        provisional.remove(uuid)
        enterSpectator(player)
        snapshots.updateSessionId(uuid, sessionId)
        sendGuestStarted(uuid, player.name, sessionId, stored.snapshot)
        beginRichFlow(player, uuid, restored = true, overLimit = counter.count(uuid) >= config.sessionLimit)
        counter.increment(uuid)
    }

    private fun startGuestSession(player: Player, snapshot: PlayerSnapshot, persist: Boolean, overLimit: Boolean = false) {
        val uuid = player.uniqueId
        val sessionId = newSessionId()
        sessions[uuid] = GuestSession(sessionId, player.name, snapshot)
        provisional.remove(uuid)
        enterSpectator(player)
        if (persist) {
            snapshots.save(uuid, StoredSnapshot(sessionId, System.currentTimeMillis(), snapshot))
        }
        sendGuestStarted(uuid, player.name, sessionId, snapshot)
        beginRichFlow(player, uuid, restored = false, overLimit = overLimit)
        counter.increment(uuid)
    }

    /** welcome title + chat prompt + the periodic subtle reminder loop */
    private fun beginRichFlow(player: Player, uuid: UUID, restored: Boolean, overLimit: Boolean) {
        messages.sendWelcome(player, restored)
        showPrompt(player, null)
        if (overLimit) messages.sendLimitWarning(player)
        scheduleReminder(uuid, player)
    }

    private fun scheduleReminder(uuid: UUID, player: Player) {
        val periodTicks = config.reminderIntervalSeconds * 20
        // folia's entity scheduler hands back null once the entity is retiring
        val task = player.scheduler.runAtFixedRate(
            plugin,
            Consumer<ScheduledTask> {
                // player may have left without a quit event racing us; the retire hook
                // below covers the rest
                if (player.isOnline && sessions.containsKey(uuid)) messages.sendReminder(player)
            },
            Runnable { reminderTasks.remove(uuid) },
            periodTicks,
            periodTicks,
        ) ?: return
        reminderTasks.put(uuid, task)?.cancel()
    }

    private fun cancelReminder(uuid: UUID) {
        reminderTasks.remove(uuid)?.cancel()
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
        cancelReminder(uuid)
        val session = sessions.remove(uuid) ?: return
        modeTransitions.add(uuid)
        try {
            session.snapshot.applyTo(player)
        } finally {
            modeTransitions.remove(uuid)
        }
        snapshots.complete(uuid)
        sendEnvelope(
            messageEnvelope(
                MessageTypes.GUEST_ENDED,
                GuestEndedPayload(uuid.toString(), session.sessionId, "AUTHENTICATED"),
            ),
        )
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
}
