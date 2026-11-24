package kz.bejiihiu.servercore.spec

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** effects port that records instead of touching a world, for state machine tests */
internal class RecordingEffects : SpecEffects {

    val begun = mutableListOf<SpecSession>()
    val ended = mutableListOf<Pair<SpecSession, ExitReason>>()
    private val pending = mutableMapOf<SpecSession, (Boolean) -> Unit>()

    override fun beginObservation(adminId: UUID, target: ObservationTarget, session: SpecSession, onComplete: (Boolean) -> Unit) {
        begun.add(session)
        pending[session] = onComplete
    }

    override fun endObservation(session: SpecSession, reason: ExitReason) {
        ended.add(session to reason)
    }

    fun complete(session: SpecSession, ok: Boolean) {
        pending.remove(session)?.invoke(ok)
    }
}

class SessionCoordinatorTest {

    @TempDir
    lateinit var dir: Path

    private fun audit(): SpecAuditWriter = SpecAuditWriter(
        baseFile = dir.resolve("audit.jsonl"),
        maxFileBytes = 1_000_000,
        maxKept = 5,
        io = Runnable::run,
    )

    private fun target(name: String = "steve") = ObservationTarget(UUID.randomUUID(), name, levelIndex = null)

    @Test
    fun `start claims the registry and activation flips the phase`() {
        val effects = RecordingEffects()
        val auditFile = dir.resolve("audit.jsonl")
        val coordinator = SessionCoordinator(effects, audit())

        val admin = UUID.randomUUID()
        val outcome = coordinator.start(admin, "admin", target("steve"))

        assertEquals(StartOutcome.Started, outcome)
        val session = requireNotNull(coordinator.activeSession(admin))
        assertEquals(SessionPhase.STARTING, session.phase)

        effects.complete(session, ok = true)
        assertEquals(SessionPhase.ACTIVE, session.phase)
        assertEquals(admin, coordinator.adminObserving(session.targetId))

        val lines = Files.readAllLines(auditFile)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("SPEC_STARTED"))
    }

    @Test
    fun `failed world start rolls the claim back`() {
        val effects = RecordingEffects()
        val auditFile = dir.resolve("audit.jsonl")
        val coordinator = SessionCoordinator(effects, audit())

        val admin = UUID.randomUUID()
        coordinator.start(admin, "admin", target("steve"))
        val claimed = coordinator.activeSession(admin)!!
        effects.complete(claimed, ok = false)

        assertNull(coordinator.activeSession(admin))
        val lines = Files.readAllLines(auditFile)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("SPEC_DENIED"))
    }

    @Test
    fun `a second start for the same admin conflicts`() {
        val effects = RecordingEffects()
        val coordinator = SessionCoordinator(effects, null)

        val admin = UUID.randomUUID()
        coordinator.start(admin, "admin", target("steve"))
        assertEquals(StartOutcome.AdminBusy, coordinator.start(admin, "admin", target("alex")))
    }

    @Test
    fun `two admins on one target is a conflicting session`() {
        val effects = RecordingEffects()
        val coordinator = SessionCoordinator(effects, null)

        val steve = target("steve")
        coordinator.start(UUID.randomUUID(), "admin-1", steve)
        assertEquals(StartOutcome.TargetBusy, coordinator.start(UUID.randomUUID(), "admin-2", steve))
    }

    @Test
    fun `exit ends the session and frees both slots`() {
        val effects = RecordingEffects()
        val auditFile = dir.resolve("audit.jsonl")
        val coordinator = SessionCoordinator(effects, audit())

        val admin = UUID.randomUUID()
        coordinator.start(admin, "admin", target("steve"))
        val session = coordinator.activeSession(admin)!!
        effects.complete(session, ok = true)

        assertEquals(ExitOutcome.Ended, coordinator.exit(admin, ExitReason.COMMAND))
        assertNull(coordinator.activeSession(admin))
        assertNull(coordinator.adminObserving(session.targetId))
        assertEquals(1, effects.ended.size)
        assertEquals(ExitReason.COMMAND, effects.ended[0].second)

        val lines = Files.readAllLines(auditFile)
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("SPEC_ENDED"))
        assertTrue(lines[1].contains("COMMAND"))
    }

    @Test
    fun `exit without a session is a no`() {
        val coordinator = SessionCoordinator(RecordingEffects(), null)
        assertEquals(ExitOutcome.NoSession, coordinator.exit(UUID.randomUUID(), ExitReason.COMMAND))
    }

    @Test
    fun `double exit stays a no`() {
        val effects = RecordingEffects()
        val coordinator = SessionCoordinator(effects, null)

        val admin = UUID.randomUUID()
        coordinator.start(admin, "admin", target("steve"))
        assertEquals(ExitOutcome.Ended, coordinator.exit(admin, ExitReason.COMMAND))
        assertEquals(ExitOutcome.NoSession, coordinator.exit(admin, ExitReason.COMMAND))
        // exactly one restore, never two
        assertEquals(1, effects.ended.size)
    }

    @Test
    fun `admin quit ends their session`() {
        val effects = RecordingEffects()
        val coordinator = SessionCoordinator(effects, null)

        val admin = UUID.randomUUID()
        coordinator.start(admin, "admin", target("steve"))
        coordinator.handleQuit(admin)

        assertNull(coordinator.activeSession(admin))
        assertEquals(ExitReason.ADMIN_QUIT, effects.ended.single().second)
    }

    @Test
    fun `target quit ends the session of whoever watched them`() {
        val effects = RecordingEffects()
        val coordinator = SessionCoordinator(effects, null)

        val admin = UUID.randomUUID()
        val steve = target("steve")
        coordinator.start(admin, "admin", steve)
        coordinator.handleQuit(steve.uuid)

        assertNull(coordinator.activeSession(admin))
        assertEquals(ExitReason.TARGET_QUIT, effects.ended.single().second)
    }

    @Test
    fun `a session exited mid-start cannot be resurrected by a late activation`() {
        val effects = RecordingEffects()
        val auditFile = dir.resolve("audit.jsonl")
        val coordinator = SessionCoordinator(effects, audit())

        val admin = UUID.randomUUID()
        coordinator.start(admin, "admin", target("steve"))
        val session = coordinator.activeSession(admin)!!
        coordinator.exit(admin, ExitReason.ADMIN_QUIT)

        // the world finishes the start afterwards - it must be ignored
        effects.complete(session, ok = true)

        assertNull(coordinator.activeSession(admin))
        assertEquals(SessionPhase.ENDING, session.phase)
        val lines = Files.readAllLines(auditFile)
        // only the SPEC_ENDED line from the exit, no SPEC_STARTED resurrection
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("SPEC_ENDED"))
    }

    @Test
    fun `shutdown ends every live session exactly once`() {
        val effects = RecordingEffects()
        val coordinator = SessionCoordinator(effects, null)

        coordinator.start(UUID.randomUUID(), "admin-1", target("steve"))
        coordinator.start(UUID.randomUUID(), "admin-2", target("alex"))
        coordinator.shutdownAll()

        assertEquals(2, effects.ended.size)
        assertTrue(effects.ended.all { it.second == ExitReason.SERVER_SHUTDOWN })
    }
}
