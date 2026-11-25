package kz.bejiihiu.servercore.spec

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import kz.bejiihiu.servercore.protection.AllowedGamemodeOverrideRegistry
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.event.player.PlayerTeleportEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * the session state machine without a server: the world port runs every hop
 * inline, so capture -> mutate -> journal -> restore is asserted step by step.
 */
class FoliaSpecEffectsTest {

    @TempDir
    lateinit var dir: Path

    private val adminId = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
    private val targetId = UUID.fromString("1b4b2f2e-1c2d-3e4f-5a6b-7c8d9e0f1a2b")

    private val home = Location(null, 12.5, 64.0, -3.25, 90f, -10f)
    private val targetSpot = Location(null, 100.0, 70.0, 200.0, 0f, 0f)

    /** inline world: no schedulers, every hop runs on the calling thread */
    private class FakeActor(
        override val id: UUID,
        override val name: String,
    ) : SpecActor {

        override var isOnline = true
        override var location: Location = Location(null, 0.0, 64.0, 0.0)
        override var gameMode: GameMode = GameMode.SURVIVAL
        override var allowFlight: Boolean = false
        override var isFlying: Boolean = false
        override var walkSpeed: Float = 0.2f
        override var flySpeed: Float = 0.1f

        /** the world's answer to the next teleport */
        var teleportResult = true

        /** entity retired: every scheduler hop bounces to onRejected */
        var rejectSchedules = false

        /** hooks for assertions that need the state at hop time */
        var onSchedule: (() -> Unit)? = null
        var onTeleport: (() -> Unit)? = null

        val teleports = mutableListOf<Location>()
        var spectatorTarget: SpecActor? = null
        var closedInventory = 0

        override fun teleport(destination: Location, cause: PlayerTeleportEvent.TeleportCause): CompletableFuture<Boolean> {
            teleports.add(destination)
            onTeleport?.invoke()
            return CompletableFuture.completedFuture(teleportResult)
        }

        override fun spectate(target: SpecActor) {
            spectatorTarget = target
        }

        override fun stopSpectating() {
            spectatorTarget = null
        }

        override fun closeInventory() {
            closedInventory++
        }

        override fun schedule(action: () -> Unit, onRejected: (() -> Unit)?) {
            onSchedule?.invoke()
            if (rejectSchedules) onRejected?.invoke() else action()
        }
    }

    private class FakeWorld : SpecWorld {
        val actors = mutableMapOf<UUID, FakeActor>()
        var worldLoaded = true

        fun add(actor: FakeActor) {
            actors[actor.id] = actor
        }

        override fun actor(id: UUID): SpecActor? = actors[id]?.takeIf { it.isOnline }

        override fun journalLocation(record: PendingRestore): Location? =
            if (worldLoaded) Location(null, record.x, record.y, record.z, record.yaw, record.pitch) else null
    }

    private class RecordingObserver : HiddenObserver {
        val applied = mutableListOf<Triple<UUID, UUID, VisibilityState>>()

        override fun apply(viewer: UUID, target: UUID, state: VisibilityState) {
            applied.add(Triple(viewer, target, state))
        }
    }

    private fun newJournal() = SpecRestoreJournal(dir.resolve("journal.json"), Runnable::run)

    private fun fakeWorld(admin: FakeActor, target: FakeActor) = FakeWorld().apply {
        add(admin)
        add(target)
    }

    private fun newAdmin() = FakeActor(adminId, "Admin").apply {
        gameMode = GameMode.CREATIVE
        allowFlight = true
        isFlying = true
        walkSpeed = 0.35f
        flySpeed = 0.45f
        location = home.clone()
    }

    private fun newTarget() = FakeActor(targetId, "Steve").apply { location = targetSpot.clone() }

    private fun observation() = ObservationTarget(targetId, "Steve", levelIndex = null)

    private fun newSession() = SpecSession(
        adminId = adminId,
        adminName = "Admin",
        targetId = targetId,
        targetName = "Steve",
        sessionId = "spec-1",
    )

    private fun newEffects(
        world: FakeWorld,
        journal: SpecRestoreJournal = newJournal(),
        observer: RecordingObserver = RecordingObserver(),
        sanctions: AllowedGamemodeOverrideRegistry? = null,
    ) = FoliaSpecEffects(world, observer, journal, Logger.getLogger("spec-test"), sanctions)

    // ---- start ----

    @Test
    fun `a settled start captures the state, mutates and journals it`() {
        val admin = newAdmin()
        val target = newTarget()
        val world = fakeWorld(admin, target)
        val observer = RecordingObserver()
        val journal = newJournal()
        val session = newSession()

        var settled: Boolean? = null
        newEffects(world, journal, observer).beginObservation(adminId, observation(), session) { settled = it }

        assertEquals(true, settled)
        // capture is the pre-mutation truth
        val saved = requireNotNull(session.savedState) { "state captured before the first mutation" }
        assertEquals(GameMode.CREATIVE, saved.gamemode)
        assertEquals(home, saved.location)
        assertEquals(0.35f, saved.walkSpeed)
        assertEquals(0.45f, saved.flySpeed)
        // and the world is mutated
        assertEquals(GameMode.SPECTATOR, admin.gameMode)
        assertEquals(target, admin.spectatorTarget)
        assertEquals(targetSpot, admin.teleports.single())
        // only the target loses sight of the admin
        assertEquals(listOf(Triple(targetId, adminId, VisibilityState.HIDDEN)), observer.applied)
        // a crash from here on is recoverable
        assertEquals(GameMode.CREATIVE.name, journal.pending(adminId)?.gamemode)
    }

    // ---- abort ----

    @Test
    fun `a refused teleport puts the admin back home and restores everything`() {
        val admin = newAdmin()
        val target = newTarget()
        admin.teleportResult = false
        val journal = newJournal()
        val observer = RecordingObserver()
        val session = newSession()

        var settled: Boolean? = null
        newEffects(fakeWorld(admin, target), journal, observer)
            .beginObservation(adminId, observation(), session) { settled = it }

        assertEquals(false, settled)
        assertEquals(GameMode.CREATIVE, admin.gameMode)
        assertTrue(admin.allowFlight)
        assertTrue(admin.isFlying)
        assertEquals(0.35f, admin.walkSpeed)
        assertEquals(0.45f, admin.flySpeed)
        // spectator hop out, then the abort teleport home
        assertEquals(listOf<Location>(targetSpot, home), admin.teleports)
        // the session never became real: nothing hidden, nothing journaled
        assertTrue(observer.applied.isEmpty())
        assertNull(journal.pending(adminId))
    }

    @Test
    fun `a target quitting mid start aborts the attach and restores the admin`() {
        val admin = newAdmin()
        val target = newTarget()
        val session = newSession()
        // the target disappears while the admin is being teleported
        admin.onTeleport = { target.isOnline = false }

        var settled: Boolean? = null
        val journal = newJournal()
        newEffects(fakeWorld(admin, target), journal)
            .beginObservation(adminId, observation(), session) { settled = it }

        assertEquals(false, settled)
        assertEquals(GameMode.CREATIVE, admin.gameMode)
        assertEquals(home, admin.teleports.last())
        assertTrue(admin.teleports.size >= 2)
        assertNull(journal.pending(adminId))
    }

    // ---- exit ----

    @Test
    fun `exit restores gamemode, flight and speeds and consumes the journal`() {
        val admin = newAdmin()
        val target = newTarget()
        val journal = newJournal()
        val observer = RecordingObserver()
        val session = newSession()
        val effects = newEffects(fakeWorld(admin, target), journal, observer)
        effects.beginObservation(adminId, observation(), session) {}
        requireNotNull(journal.pending(adminId))

        effects.endObservation(session, ExitReason.COMMAND)

        assertEquals(GameMode.CREATIVE, admin.gameMode)
        assertTrue(admin.allowFlight)
        assertTrue(admin.isFlying)
        assertEquals(0.35f, admin.walkSpeed)
        assertEquals(0.45f, admin.flySpeed)
        assertEquals(home, admin.teleports.last())
        assertNull(admin.spectatorTarget)
        assertEquals(1, admin.closedInventory)
        assertEquals(Triple(targetId, adminId, VisibilityState.VISIBLE), observer.applied.last())
        assertNull(journal.pending(adminId))
    }

    @Test
    fun `flight comes back only when it was allowed before the session`() {
        val admin = newAdmin()
        admin.allowFlight = false
        admin.isFlying = true // a lying client, the restore must not hand out flight
        val session = newSession()
        val effects = newEffects(fakeWorld(admin, newTarget()))
        effects.beginObservation(adminId, observation(), session) {}

        effects.endObservation(session, ExitReason.COMMAND)

        assertEquals(GameMode.CREATIVE, admin.gameMode)
        assertFalse(admin.allowFlight)
        assertFalse(admin.isFlying)
    }

    @Test
    fun `exit with an offline admin journals the saved state instead of replaying it live`() {
        val admin = newAdmin()
        val target = newTarget()
        val journal = newJournal()
        val session = newSession()
        val effects = newEffects(fakeWorld(admin, target), journal)
        effects.beginObservation(adminId, observation(), session) {}
        journal.remove(adminId)

        admin.isOnline = false
        effects.endObservation(session, ExitReason.ADMIN_QUIT)

        // nothing live was touched, the entry waits for the next join
        assertEquals(GameMode.SPECTATOR, admin.gameMode)
        assertEquals(GameMode.CREATIVE.name, journal.pending(adminId)?.gamemode)
    }

    @Test
    fun `exit before any mutation closes the view and leaves the admin alone`() {
        val admin = newAdmin()
        val session = newSession()
        assertNull(session.savedState)

        newEffects(fakeWorld(admin, newTarget())).endObservation(session, ExitReason.TARGET_QUIT)

        assertEquals(GameMode.CREATIVE, admin.gameMode)
        assertTrue(admin.teleports.isEmpty())
        assertEquals(1, admin.closedInventory)
    }

    @Test
    fun `a restore hop that never lands keeps the journal entry`() {
        val admin = newAdmin()
        val journal = newJournal()
        val session = newSession()
        val effects = newEffects(fakeWorld(admin, newTarget()), journal)
        effects.beginObservation(adminId, observation(), session) {}
        journal.remove(adminId)

        admin.rejectSchedules = true
        effects.endObservation(session, ExitReason.ADMIN_QUIT)

        assertEquals(GameMode.SPECTATOR, admin.gameMode)
        assertEquals(GameMode.CREATIVE.name, journal.pending(adminId)?.gamemode)
    }

    // ---- journal replay ----

    private fun record(gamemode: String = "CREATIVE") = PendingRestore(
        adminId = adminId.toString(),
        world = "world",
        x = 1.5,
        y = 64.0,
        z = -3.25,
        yaw = 90f,
        pitch = -10f,
        gamemode = gamemode,
        flying = false,
        allowFlight = true,
        walkSpeed = 0.4f,
        flySpeed = 0.6f,
    )

    @Test
    fun `replay applies the journal entry, keeps its own grant and clears the entry`() {
        val admin = FakeActor(adminId, "Admin").apply { gameMode = GameMode.SPECTATOR }
        val journal = newJournal()
        val sanctions = AllowedGamemodeOverrideRegistry()
        val effects = newEffects(FakeWorld().apply { add(admin) }, journal, sanctions = sanctions)
        journal.put(record())

        // the restore event fires after the teleport: the grant has to exist then
        var sanctionedDuringRestore = false
        admin.onTeleport = { sanctionedDuringRestore = sanctions.isSanctioned(adminId, GameMode.CREATIVE) }

        effects.replayRestore(adminId, requireNotNull(journal.pending(adminId)))

        assertTrue(sanctionedDuringRestore, "the replay has to sanction the mode it restores")
        assertEquals(GameMode.CREATIVE, admin.gameMode)
        assertTrue(admin.allowFlight)
        assertFalse(admin.isFlying)
        assertEquals(0.4f, admin.walkSpeed)
        assertEquals(0.6f, admin.flySpeed)
        assertEquals(Location(null, 1.5, 64.0, -3.25, 90f, -10f), admin.teleports.single())
        // grant consumed with the restore, entry consumed with the replay
        assertTrue(sanctions.sanctionedModes(adminId).isEmpty())
        assertNull(journal.pending(adminId))
    }

    @Test
    fun `replay of a survival record needs no grant`() {
        val admin = FakeActor(adminId, "Admin").apply { gameMode = GameMode.SPECTATOR }
        val journal = newJournal()
        val sanctions = AllowedGamemodeOverrideRegistry()
        val effects = newEffects(FakeWorld().apply { add(admin) }, journal, sanctions = sanctions)
        journal.put(record(gamemode = "SURVIVAL"))

        effects.replayRestore(adminId, requireNotNull(journal.pending(adminId)))

        assertEquals(GameMode.SURVIVAL, admin.gameMode)
        assertTrue(sanctions.sanctionedModes(adminId).isEmpty())
        assertNull(journal.pending(adminId))
    }

    @Test
    fun `replay into an unloaded world keeps the entry for the next join`() {
        val admin = FakeActor(adminId, "Admin").apply { gameMode = GameMode.SPECTATOR }
        val journal = newJournal()
        val world = FakeWorld().apply {
            add(admin)
            worldLoaded = false
        }
        journal.put(record())

        newEffects(world, journal).replayRestore(adminId, requireNotNull(journal.pending(adminId)))

        assertEquals(GameMode.SPECTATOR, admin.gameMode)
        assertTrue(admin.teleports.isEmpty())
        assertEquals("CREATIVE", journal.pending(adminId)?.gamemode)
    }

    // ---- the coordinator on top of the real port ----

    @Test
    fun `a coordinator run settles ACTIVE and comes back restored`() {
        val admin = newAdmin()
        val target = newTarget()
        val journal = newJournal()
        val coordinator = SessionCoordinator(newEffects(fakeWorld(admin, target), journal))

        assertEquals(StartOutcome.Started, coordinator.start(adminId, "Admin", observation()))
        assertEquals(SessionPhase.ACTIVE, coordinator.activeSession(adminId)?.phase)
        assertEquals(GameMode.SPECTATOR, admin.gameMode)
        assertEquals(target, admin.spectatorTarget)

        assertEquals(ExitOutcome.Ended, coordinator.exit(adminId, ExitReason.COMMAND))

        assertNull(coordinator.activeSession(adminId))
        assertEquals(GameMode.CREATIVE, admin.gameMode)
        assertNull(admin.spectatorTarget)
        assertNull(journal.pending(adminId))
    }

    @Test
    fun `a refused start leaves the coordinator with no session and a restored admin`() {
        val admin = newAdmin()
        admin.teleportResult = false
        val coordinator = SessionCoordinator(newEffects(fakeWorld(admin, newTarget())))

        assertEquals(StartOutcome.Started, coordinator.start(adminId, "Admin", observation()))

        // the claim rolled back, the admin is back in their own mode at home
        assertEquals(0, coordinator.activeCount())
        assertEquals(GameMode.CREATIVE, admin.gameMode)
        assertEquals(home, admin.teleports.last())
    }
}
