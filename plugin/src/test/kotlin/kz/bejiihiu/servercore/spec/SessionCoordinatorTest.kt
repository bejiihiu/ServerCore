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

/** every lifecycle callback the coordinator fires, in order */
internal class RecordingListener : SpecSessionListener {

    val claimed = mutableListOf<SpecSession>()
    val started = mutableListOf<SpecSession>()
    val aborted = mutableListOf<SpecSession>()
    val ended = mutableListOf<Pair<SpecSession, ExitReason>>()

    override fun onClaimed(session: SpecSession) {
        claimed.add(session)
    }

    override fun onStarted(session: SpecSession) {
        started.add(session)
    }

    override fun onAborted(session: SpecSession) {
        aborted.add(session)
    }

    override fun onEnded(session: SpecSession, reason: ExitReason) {
        ended.add(session to reason)
    }
}

class SessionCoordinatorTest {

    @TempDir
    lateinit var dir: Path

    private fun target(name: String = "steve") = ObservationTarget(UUID.randomUUID(), name, levelIndex = null)

    private fun reportingTrail(): Pair<SpecSessionRegistry, Path> {
        val file = dir.resolve("trail")
        val registry = SpecSessionRegistry(
            sender = object : SpecEventSender {
                override fun sendSpecStarted(payload: kz.bejiihiu.servercore.websocket.Payload.SpecStarted) = Unit
                override fun sendSpecEnded(payload: kz.bejiihiu.servercore.websocket.Payload.SpecEnded) = Unit
            },
            audit = SpecAuditTrail(file, Runnable::run),
        )
        return registry to file
    }

    private fun trailEvents(file: Path): List<String> {
        // the trail was built with a synchronous io executor, so every record
        // already drained; a directory that was never created means no events
        if (!Files.exists(file)) return emptyList()
        return Files.list(file).use { stream ->
            stream.flatMap { Files.readAllLines(it).stream() }
                .filter { it.isNotBlank() }
                .map { line -> Regex("\"event\":\"([A-Z_]+)\"").find(line)?.groupValues?.get(1) ?: "" }
                .toList()
        }
    }

    @Test
    fun `start claims the registry and activation flips the phase`() {
        val effects = RecordingEffects()
        val (registry, _) = reportingTrail()
        val coordinator = SessionCoordinator(effects, registry)

        val admin = UUID.randomUUID()
        val outcome = coordinator.start(admin, "admin", target("steve"))

        assertEquals(StartOutcome.Started, outcome)
        val session = requireNotNull(coordinator.activeSession(admin))
        assertEquals(SessionPhase.STARTING, session.phase)

        effects.complete(session, ok = true)
        assertEquals(SessionPhase.ACTIVE, session.phase)
        assertEquals(admin, coordinator.adminObserving(session.targetId))
        assertEquals(1, coordinator.activeCount())
    }

    @Test
    fun `a settled start reports upstream and to the listeners`() {
        val effects = RecordingEffects()
        val listener = RecordingListener()
        val (registry, trail) = reportingTrail()
        val coordinator = SessionCoordinator(effects, registry, listOf(listener))

        val admin = UUID.randomUUID()
        coordinator.start(admin, "admin", target("steve"))
        val session = coordinator.activeSession(admin)!!
        effects.complete(session, ok = true)

        assertEquals(listOf(session), listener.claimed)
        assertEquals(listOf(session), listener.started)
        assertTrue(listener.aborted.isEmpty())
        assertEquals(listOf(SpecAuditEvents.START), trailEvents(trail))
    }

    @Test
    fun `failed world start rolls the claim back and denies`() {
        val effects = RecordingEffects()
        val listener = RecordingListener()
        val (registry, trail) = reportingTrail()
        val coordinator = SessionCoordinator(effects, registry, listOf(listener))

        val admin = UUID.randomUUID()
        coordinator.start(admin, "admin", target("steve"))
        val claimed = coordinator.activeSession(admin)!!
        effects.complete(claimed, ok = false)

        assertNull(coordinator.activeSession(admin))
        assertEquals(0, coordinator.activeCount())
        assertEquals(listOf(claimed), listener.aborted)
        assertTrue(listener.started.isEmpty())
        assertEquals(listOf(SpecAuditEvents.DENIED), trailEvents(trail))
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
    fun `exit ends the session, frees both slots and reports the end`() {
        val effects = RecordingEffects()
        val listener = RecordingListener()
        val (registry, trail) = reportingTrail()
        val coordinator = SessionCoordinator(effects, registry, listOf(listener))

        val admin = UUID.randomUUID()
        coordinator.start(admin, "admin", target("steve"))
        val session = coordinator.activeSession(admin)!!
        effects.complete(session, ok = true)

        assertEquals(ExitOutcome.Ended, coordinator.exit(admin, ExitReason.COMMAND))
        assertNull(coordinator.activeSession(admin))
        assertNull(coordinator.adminObserving(session.targetId))
        assertEquals(1, effects.ended.size)
        assertEquals(ExitReason.COMMAND, effects.ended[0].second)
        assertEquals(session to ExitReason.COMMAND, listener.ended.single())
        assertEquals(listOf(SpecAuditEvents.START, SpecAuditEvents.END), trailEvents(trail))
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
        val listener = RecordingListener()
        val (registry, trail) = reportingTrail()
        val coordinator = SessionCoordinator(effects, registry, listOf(listener))

        val admin = UUID.randomUUID()
        coordinator.start(admin, "admin", target("steve"))
        val session = coordinator.activeSession(admin)!!
        coordinator.exit(admin, ExitReason.ADMIN_QUIT)

        // the world finishes the start afterwards - it must be ignored
        effects.complete(session, ok = true)

        assertNull(coordinator.activeSession(admin))
        assertEquals(SessionPhase.ENDING, session.phase)
        // the session was never reported as started, so no end report either:
        // nothing upstream ever heard of it
        assertTrue(trailEvents(trail).isEmpty())
        assertEquals(1, listener.ended.size)
        assertTrue(listener.started.isEmpty())
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
