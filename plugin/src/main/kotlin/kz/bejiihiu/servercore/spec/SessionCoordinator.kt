package kz.bejiihiu.servercore.spec

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** why a session ended, feeds the audit line */
enum class ExitReason { COMMAND, TARGET_QUIT, ADMIN_QUIT, SERVER_SHUTDOWN }

/** what the effects port does with an agreed-upon session transition */
interface SpecEffects {
    /** applies spectator mode + camera + hiding, then reports the outcome exactly once */
    fun beginObservation(adminId: UUID, target: ObservationTarget, session: SpecSession, onComplete: (Boolean) -> Unit)

    /** restores the admin from the session's saved state; must be safe for offline admins */
    fun endObservation(session: SpecSession, reason: ExitReason)
}

/** minimal target identity bundle so the coordinator never touches bukkit objects */
data class ObservationTarget(
    val uuid: UUID,
    val name: String,
    val levelIndex: Int?,
)

sealed interface StartOutcome {
    data object Started : StartOutcome
    data object AdminBusy : StartOutcome
    data object TargetBusy : StartOutcome
}

sealed interface ExitOutcome {
    data object Ended : ExitOutcome
    data object NoSession : ExitOutcome
}

/**
 * the session state machine and the single owner of "who is watching whom":
 * claims/releases registry slots, drives phases, delegates every world touch to
 * [SpecEffects] and notifies the reporting registry plus the session listeners.
 * one session per admin, and one observer per target - two admins on one target
 * counts as conflicting sessions.
 *
 * all transitions serialize on one lock; the effects callbacks run outside it
 * so a slow world touch never blocks a quit handler.
 */
class SessionCoordinator(
    private val effects: SpecEffects,
    /** ws + local audit reporting bridge, driven on every settled transition */
    private val reports: SpecSessionRegistry? = null,
    /** lifecycle side effects (gamemode sanction, packet hiding, inventory view) */
    private val listeners: List<SpecSessionListener> = emptyList(),
) {

    private val lock = ReentrantLock()
    private val sessions = HashMap<UUID, SpecSession>() // admin uuid -> session
    private val observers = HashMap<UUID, UUID>() // target uuid -> admin uuid

    fun activeSession(adminId: UUID): SpecSession? = lock.withLock { sessions[adminId] }

    fun adminObserving(targetId: UUID): UUID? = lock.withLock { observers[targetId] }

    fun activeCount(): Int = lock.withLock { sessions.size }

    /**
     * atomically claims both registry slots and hands the session to the
     * effects port. the caller resolved permissions and the target already;
     * conflicts are decided here, last, on fresh data. [onSettled] fires when
     * the world actually accepted the session (or refused it) - conflicts and
     * refusals earlier in the chain never reach it.
     */
    fun start(
        adminId: UUID,
        adminName: String,
        target: ObservationTarget,
        onSettled: (Boolean) -> Unit = {},
    ): StartOutcome = lock.withLock {
        if (sessions.containsKey(adminId)) return StartOutcome.AdminBusy
        if (observers.containsKey(target.uuid)) return StartOutcome.TargetBusy

        val session = SpecSession(
            adminId = adminId,
            adminName = adminName,
            targetId = target.uuid,
            targetName = target.name,
        )
        sessions[adminId] = session
        observers[target.uuid] = adminId
        // claimed-but-not-started: sanction the gamemode switch now, the guard
        // event fires the moment the effects port touches the mode
        listeners.forEach { it.onClaimed(session) }
        effects.beginObservation(adminId, target, session) { succeeded ->
            // the ACTIVE flip must be atomic with the ownership check: exit()
            // flips to ENDING under the same lock, so a session that already
            // ended can never be resurrected here
            val transitioned = lock.withLock {
                if (succeeded && sessions[adminId] === session && session.phase != SessionPhase.ENDING) {
                    session.phase = SessionPhase.ACTIVE
                    true
                } else {
                    false
                }
            }
            if (succeeded && transitioned) {
                reports?.started(session)
                listeners.forEach { it.onStarted(session) }
                onSettled(true)
            } else if (transitioned || lock.withLock { sessions[adminId] === session }) {
                // world refused the start (target vanished, teleport failed): roll the claim back
                lock.withLock {
                    if (sessions[adminId] === session) sessions.remove(adminId)
                    if (observers[target.uuid] === adminId) observers.remove(target.uuid)
                }
                session.phase = SessionPhase.ENDING
                listeners.forEach { it.onAborted(session) }
                reports?.denied(
                    adminId, adminName, target.uuid, target.name,
                    "start aborted: world refused the transition",
                )
                onSettled(false)
            }
            // else: the session was already exited, that path owns the reporting
        }
        StartOutcome.Started
    }

    /**
     * ends the session for whatever reason. idempotent: a second call for an
     * already-ending session is a NoSession, never a double restore.
     */
    fun exit(adminId: UUID, reason: ExitReason): ExitOutcome {
        val (session, settled) = lock.withLock {
            val active = sessions[adminId] ?: return ExitOutcome.NoSession
            // only sessions the world actually accepted were reported as
            // started; the rest get no end report, nothing was ever announced
            val wasSettled = active.phase == SessionPhase.ACTIVE
            active.phase = SessionPhase.ENDING
            sessions.remove(adminId)
            if (observers[active.targetId] === adminId) observers.remove(active.targetId)
            active to wasSettled
        }
        // listeners first: the packet restore and the viewer close must not
        // queue behind the effects port's teleport chain, and the restore
        // sanction grant has to exist before the gamemode event fires
        listeners.forEach { it.onEnded(session, reason) }
        effects.endObservation(session, reason)
        if (settled) reports?.ended(session, reason)
        return ExitOutcome.Ended
    }

    /** quit/unload path: a departing uuid either owns a session or is being watched */
    fun handleQuit(playerId: UUID) {
        val owning = activeSession(playerId)
        if (owning != null) {
            exit(playerId, ExitReason.ADMIN_QUIT)
            return
        }
        val observer = adminObserving(playerId)
        if (observer != null) {
            exit(observer, ExitReason.TARGET_QUIT)
        }
    }

    /**
     * shutdown sweep. sessions never survive a restart - they end here, and any
     * admin state that cannot be restored live travels through the effects port
     * (restore journal) instead.
     */
    fun shutdownAll() {
        for (adminId in lock.withLock { sessions.keys.toSet() }) {
            exit(adminId, ExitReason.SERVER_SHUTDOWN)
        }
    }
}
