package kz.bejiihiu.servercore.spec

import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kz.bejiihiu.servercore.protection.AllowedGamemodeOverrideRegistry
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.Plugin

/**
 * the folia half of the session machine: every bukkit mutation of the admin
 * runs on the admin's own entity scheduler, the teleport goes through
 * teleportAsync, and every step re-checks the session phase so a session ended
 * mid-flight never resurrects itself after the restore already ran.
 *
 * sequence per start: capture state -> spectator -> teleport to the target ->
 * attach camera -> hide from the target. per exit: detach camera -> close open
 * views -> teleport home -> gamemode/flight/speeds back -> show to the target.
 * if a live restore is impossible (admin gone), the saved state lands in the
 * restore journal and is replayed on the next join.
 */
class FoliaSpecEffects(
    private val plugin: Plugin,
    private val hiddenObserver: HiddenObserver,
    private val journal: SpecRestoreJournal,
    private val logger: Logger,
    /** gamemode sanction source for the journal replay, see [replayRestore] */
    private val sanctions: AllowedGamemodeOverrideRegistry? = null,
) : SpecEffects {

    override fun beginObservation(adminId: UUID, target: ObservationTarget, session: SpecSession, onComplete: (Boolean) -> Unit) {
        val admin = plugin.server.getPlayer(adminId)
        val targetPlayer = plugin.server.getPlayer(target.uuid)
        if (admin == null || targetPlayer == null || !admin.isOnline || !targetPlayer.isOnline) {
            onComplete(false)
            return
        }
        // capture the target's position on the target's own thread first: a
        // cross-region location read is exactly what folia forbids
        targetPlayer.scheduler.run(
            plugin,
            { targetTask ->
                val destination = targetPlayer.location
                targetTask.cancel()
                admin.scheduler.run(
                    plugin,
                    { adminTask ->
                        if (session.phase != SessionPhase.STARTING) {
                            adminTask.cancel()
                            onComplete(false)
                            return@run
                        }
                        // saved state first, then mutate: the invariant is that
                        // a non-null savedState means something was already changed
                        session.savedState = SavedAdminState(
                            gamemode = admin.gameMode,
                            location = admin.location.clone(),
                            flying = admin.isFlying,
                            allowFlight = admin.allowFlight,
                            walkSpeed = admin.walkSpeed,
                            flySpeed = admin.flySpeed,
                        )
                        admin.gameMode = GameMode.SPECTATOR
                        adminTask.cancel()
                        admin.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.SPECTATE)
                            .whenComplete { moved, error ->
                                // the future completes on some region thread - hop back
                                // onto the admin's scheduler before touching the player
                                admin.scheduler.run(
                                    plugin,
                                    { attachTask ->
                                        if (session.phase != SessionPhase.STARTING || moved != true || error != null) {
                                            attachTask.cancel()
                                            // an exit may have interleaved with our teleport (phase already
                                            // ENDING), or the world refused the start: either way the admin
                                            // goes home with the saved mode back. endObservation may have
                                            // read savedState before our capture became visible to it, so
                                            // this is the path that actually restores - a second restore
                                            // alongside a concurrent one is idempotent, a missing one
                                            // strands the admin in spectator
                                            abortRestore(admin, session)
                                            onComplete(false)
                                            return@run
                                        }
                                        if (!targetPlayer.isOnline) {
                                            attachTask.cancel()
                                            abortRestore(admin, session)
                                            onComplete(false)
                                            return@run
                                        }
                                        admin.setSpectatorTarget(targetPlayer)
                                        // hidden from the observed player specifically, never globally
                                        hiddenObserver.apply(targetPlayer.uniqueId, admin.uniqueId, VisibilityState.HIDDEN)
                                        // the state is now mutated: journal it so a hard crash mid-session
                                        // still leaves the admin recoverable on their next join
                                        session.savedState?.let { journal.put(it.toRecord(admin.uniqueId)) }
                                        attachTask.cancel()
                                        onComplete(true)
                                    },
                                    { onComplete(false) },
                                )
                            }
                    },
                    { onComplete(false) },
                )
            },
            { onComplete(false) },
        )
    }

    override fun endObservation(session: SpecSession, reason: ExitReason) {
        val admin = plugin.server.getPlayer(session.adminId)
        val saved = session.savedState
        if (admin == null || !admin.isOnline) {
            // nothing live to restore: the journal replays it on the next join
            if (saved != null) journal.put(saved.toRecord(session.adminId))
            return
        }
        // hide cleanup works even for offline targets (no-op inside the observer)
        hiddenObserver.apply(session.targetId, session.adminId, VisibilityState.VISIBLE)

        admin.scheduler.run(
            plugin,
            { task ->
                task.cancel()
                // a session ended while the start sequence was still in flight:
                // skip straight to restore, never finish attaching the camera
                session.closeables.forEach { closeable -> runCatching { closeable.close() } }
                admin.closeInventory()
                if (saved == null) {
                    // no state was ever mutated, nothing to restore
                    return@run
                }
                runCatching { admin.setSpectatorTarget(null) }
                admin.teleportAsync(saved.location.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete { _, _ ->
                        admin.scheduler.run(
                            plugin,
                            { restoreTask ->
                                restoreTask.cancel()
                                restore(admin, saved)
                                // live restore done, the journal entry has served its purpose
                                journal.remove(session.adminId)
                            },
                            { journal.put(saved.toRecord(session.adminId)) },
                        )
                    }
            },
            // entity retired before the task ran: journal the state instead
            {
                if (saved != null) journal.put(saved.toRecord(session.adminId))
            },
        )
    }

    /**
     * a refused start left the admin in spectator, possibly stranded at the
     * target's position: send them home and put the saved mode back. the
     * coordinator has already torn the claim down, nobody else will.
     */
    private fun abortRestore(admin: Player, session: SpecSession) {
        val saved = session.savedState ?: return
        admin.teleportAsync(saved.location.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN)
            .whenComplete { _, _ ->
                admin.scheduler.run(
                    plugin,
                    { task ->
                        task.cancel()
                        restore(admin, saved)
                    },
                    // admin gone mid-flight: the journal replays it on their next join
                    { journal.put(saved.toRecord(session.adminId)) },
                )
            }
    }

    /** replay path for the restore journal: called on join with a fresh entity */
    fun replayRestore(admin: Player, record: PendingRestore) {
        val world = plugin.server.getWorld(record.world)
        if (world == null) {
            logger.warning("spec restore: world ${record.world} is not loaded, keeping the journal entry")
            return
        }
        val location = Location(world, record.x, record.y, record.z, record.yaw, record.pitch)
        // the session's restore grant died with the session (short ttl), and
        // the guard cancels any non-survival mode change without a live one -
        // an admin rejoining after the ttl would otherwise stay stranded in
        // spectator while the sweep pushed them to survival. the replay
        // carries its own grant, valid only for the mode it restores
        val mode = runCatching { GameMode.valueOf(record.gamemode) }.getOrNull()
        if (mode != null && mode != GameMode.SURVIVAL) {
            sanctions?.grant(
                admin.uniqueId,
                setOf(mode),
                REPLAY_SOURCE,
                ttlMillis = TimeUnit.SECONDS.toMillis(REPLAY_TTL_SECONDS),
            )
        }
        admin.scheduler.run(
            plugin,
            { task ->
                task.cancel()
                admin.teleportAsync(location, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete { _, _ ->
                        admin.scheduler.run(
                            plugin,
                            { restoreTask ->
                                restoreTask.cancel()
                                restore(admin, record)
                                if (mode != null && mode != GameMode.SURVIVAL) {
                                    sanctions?.revoke(admin.uniqueId, REPLAY_SOURCE)
                                }
                                journal.remove(UUID.fromString(record.adminId))
                            },
                            null,
                        )
                    }
            },
            null,
        )
    }

    private fun restore(admin: Player, saved: SavedAdminState) {
        runCatching {
            admin.gameMode = saved.gamemode
            admin.allowFlight = saved.allowFlight
            admin.isFlying = saved.flying && saved.allowFlight
            admin.walkSpeed = saved.walkSpeed
            admin.flySpeed = saved.flySpeed
        }.onFailure { error ->
            logger.warning("spec restore failed for ${admin.name}: $error")
        }
    }

    private fun restore(admin: Player, record: PendingRestore) {
        runCatching {
            admin.gameMode = GameMode.valueOf(record.gamemode)
            admin.allowFlight = record.allowFlight
            admin.isFlying = record.flying && record.allowFlight
            admin.walkSpeed = record.walkSpeed
            admin.flySpeed = record.flySpeed
        }.onFailure { error ->
            logger.warning("spec journal restore failed for ${admin.name}: $error")
        }
    }

    private companion object {
        const val REPLAY_SOURCE = "spec-restore-replay"

        /** the replay restore fires within a few scheduler hops of the join, this is generous */
        const val REPLAY_TTL_SECONDS = 60L
    }
}

/** the journal needs world-serializable coordinates, not a live location handle */
internal fun SavedAdminState.toRecord(adminId: UUID): PendingRestore = PendingRestore(
    adminId = adminId.toString(),
    world = location.world?.name ?: "",
    x = location.x,
    y = location.y,
    z = location.z,
    yaw = location.yaw,
    pitch = location.pitch,
    gamemode = gamemode.name,
    flying = flying,
    allowFlight = allowFlight,
    walkSpeed = walkSpeed,
    flySpeed = flySpeed,
)
