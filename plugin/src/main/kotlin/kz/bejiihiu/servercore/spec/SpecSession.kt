package kz.bejiihiu.servercore.spec

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.bukkit.GameMode
import org.bukkit.Location

/**
 * everything needed to put the admin back where they came from. captured on
 * the admin's own entity thread right before anything is mutated.
 */
data class SavedAdminState(
    val gamemode: GameMode,
    val location: Location,
    val flying: Boolean,
    val allowFlight: Boolean,
    val walkSpeed: Float,
    val flySpeed: Float,
)

/**
 * lifecycle of one observation. STARTING covers the window between the atomic
 * claim and the camera actually attaching (gamemode switch + teleport are two
 * hops); only ACTIVE sessions are reported as started in the audit.
 */
enum class SessionPhase { STARTING, ACTIVE, ENDING }

/**
 * one observation session, exactly one per admin at a time. pure data, all
 * bukkit side effects live in the effects port, so this model is unit-testable.
 *
 * sessions live in memory only: a server restart never brings them back, the
 * admin's saved state travels through the restore journal instead.
 */
class SpecSession(
    val adminId: UUID,
    val adminName: String,
    val targetId: UUID,
    val targetName: String,
    /** state captured before the first mutation, null until the effects port got to run */
    @Volatile var savedState: SavedAdminState? = null,
    /** wire identity of this session, handed out to the backend on SPEC_STARTED */
    val sessionId: String = UUID.randomUUID().toString(),
    /** started-at wall clock, audit bookkeeping; injectable so tests can age sessions */
    val startedAtMs: Long = System.currentTimeMillis(),
) {

    @Volatile
    var phase: SessionPhase = SessionPhase.STARTING
        internal set

    /**
     * viewer interfaces and other per-session handles that must be shut when the
     * session dies, no matter why. thread-safe: effects threads may register
     * while the exit path iterates.
     */
    val closeables: MutableList<AutoCloseable> = CopyOnWriteArrayList()
}
