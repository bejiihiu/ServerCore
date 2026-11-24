package kz.bejiihiu.servercore.sync

import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * the invalidation matrix: every transition from the spec's packet-state sync
 * list, checked against what happens on the viewer side and on the target side.
 * pure logic — real in-memory registry, recording executor, no runtime.
 */
class InvalidationMatrixTest {

    private lateinit var states: InMemoryViewStateRegistry
    private lateinit var resync: RecordingResync
    private lateinit var identities: EntityIdentityTracker
    private lateinit var sync: PacketStateSync

    // stable fake participants
    private val viewer = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val bystander = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val target = UUID.fromString("00000000-0000-0000-0000-000000000003")

    @BeforeEach
    fun setUp() {
        states = InMemoryViewStateRegistry()
        resync = RecordingResync()
        identities = EntityIdentityTracker()
        sync = PacketStateSync.create(states, resync, identities)!!
    }

    @AfterEach
    fun tearDown() {
        // every created sync holds the process-wide double-load claim
        sync.release()
    }

    private fun seedViewerRows() {
        states.set(viewer, target, ViewState.DISGUISED)
        states.set(viewer, bystander, ViewState.VISIBLE)
    }

    private fun seedTargetRows() {
        states.set(viewer, target, ViewState.DISGUISED)
        states.set(bystander, target, ViewState.DISGUISED)
    }

    // -- reconnect ---------------------------------------------------------

    @Test
    fun `reconnect resends viewer view and reapplies target to every viewer`() {
        seedViewerRows()
        seedTargetRows()
        resync.clear()

        sync.onTransition(SyncReason.RECONNECT, target)

        // viewer side: fresh client gets its full view re-sent, rows survive
        assertTrue(resync.log().contains("resyncViewer($target)"))
        // rows: viewer->target, viewer->bystander, bystander->target
        assertEquals(3, states.rowCount())
        // target side: every viewer of the returning player gets re-applied
        assertTrue(resync.log().contains("resyncTarget($viewer,$target)"))
        assertTrue(resync.log().contains("resyncTarget($bystander,$target)"))
    }

    // -- world change ------------------------------------------------------

    @Test
    fun `world change resets viewer overrides and reapplies target state`() {
        seedViewerRows()
        resync.clear()

        sync.onTransition(SyncReason.WORLD_CHANGE, viewer)

        // viewer side: applied overrides dropped, vanilla resend, rows kept
        assertEquals(listOf("resetViewer($viewer)"), resync.log())
        assertEquals(2, states.rowCount())

        resync.clear()
        sync.onTransition(SyncReason.WORLD_CHANGE, target)
        assertTrue(resync.log().contains("resyncTarget($viewer,$target)"))
    }

    // -- teleport ----------------------------------------------------------

    @Test
    fun `cross-world teleport resets each viewer of the target fully`() {
        seedTargetRows()
        resync.clear()

        sync.onTransition(SyncReason.TELEPORT, target)

        // the teleporting player's own view resets too, then every viewer of
        // the target gets the full per-target viewer-side reset + re-apply
        assertEquals(
            listOf(
                "resetViewer($target)",
                "resetTarget($viewer,$target)",
                "resyncTarget($viewer,$target)",
                "resetTarget($bystander,$target)",
                "resyncTarget($bystander,$target)",
            ),
            resync.log(),
        )
        // the applied state comes back from the writer and updates the rows
        assertEquals(ViewState.VISIBLE, states.get(viewer, target))
        assertEquals(ViewState.VISIBLE, states.get(bystander, target))
    }

    // -- death -------------------------------------------------------------

    @Test
    fun `dead target never renders as a disguised zombie`() {
        seedTargetRows()
        states.set(viewer, bystander, ViewState.DISGUISED) // unrelated pair, untouched
        resync.clear()

        sync.onTransition(SyncReason.DEATH, target)

        // only the disguised pairs of the dead target get the vanilla restore
        assertEquals(
            listOf(
                "restoreVanilla($viewer,$target)",
                "restoreVanilla($bystander,$target)",
            ),
            resync.log(),
        )
        assertEquals(ViewState.DEAD, states.get(viewer, target))
        assertEquals(ViewState.DEAD, states.get(bystander, target))
        // unrelated disguised pair keeps its state: death is about the dead target
        assertEquals(ViewState.DISGUISED, states.get(viewer, bystander))
    }

    @Test
    fun `hidden pair stays hidden through death because hiding is policy`() {
        states.set(viewer, target, ViewState.HIDDEN)
        resync.clear()

        sync.onTransition(SyncReason.DEATH, target)

        assertTrue(resync.log().isEmpty())
        assertEquals(ViewState.HIDDEN, states.get(viewer, target))
    }

    // -- respawn -----------------------------------------------------------

    @Test
    fun `respawn clears the dead pin and reapplies the real policy`() {
        states.set(viewer, target, ViewState.DEAD)
        resync.clear()

        sync.onTransition(SyncReason.RESPAWN, target)

        // viewer side of the respawning player resets, then the pin clears
        assertEquals(
            listOf(
                "resetViewer($target)",
                "resyncTarget($viewer,$target)",
            ),
            resync.log())
        // writer reported VISIBLE -> row follows reality again, DEAD is gone
        assertEquals(ViewState.VISIBLE, states.get(viewer, target))
    }

    @Test
    fun `respawn also resets the respawning player's own viewer view`() {
        seedViewerRows()
        resync.clear()

        sync.onTransition(SyncReason.RESPAWN, viewer)

        assertEquals(listOf("resetViewer($viewer)"), resync.log())
    }

    // -- gamemode ----------------------------------------------------------

    @Test
    fun `gamemode change reasserts both directions`() {
        seedViewerRows()
        seedTargetRows()
        resync.clear()

        sync.onTransition(SyncReason.GAMEMODE_CHANGE, target)

        assertTrue(resync.log().contains("resyncViewer($target)"))
        assertTrue(resync.log().contains("resyncTarget($viewer,$target)"))
        assertTrue(resync.log().contains("resyncTarget($bystander,$target)"))
    }

    // -- equipment / entity change ----------------------------------------

    @Test
    fun `equipment change reapplies to every viewer without touching the viewer side`() {
        seedTargetRows()
        resync.clear()

        sync.onTransition(SyncReason.EQUIPMENT_CHANGE, target)

        assertEquals(
            listOf(
                "resyncTarget($viewer,$target)",
                "resyncTarget($bystander,$target)",
            ),
            resync.log(),
        )
    }

    @Test
    fun `entity change reapplies to every viewer`() {
        seedTargetRows()
        resync.clear()

        sync.onTransition(SyncReason.ENTITY_CHANGE, target)

        assertEquals(2, resync.ops().count { it == "resyncTarget" })
        assertFalse(resync.ops().contains("resetViewer"))
    }

    // -- entity removal ----------------------------------------------------

    @Test
    fun `entity removal drops all cached rows so nothing re-attaches`() {
        seedTargetRows()
        resync.clear()

        sync.onTransition(SyncReason.ENTITY_REMOVAL, target)

        assertEquals(0, states.rowCount())
        assertTrue(resync.log().isEmpty())
    }

    // -- quit --------------------------------------------------------------

    @Test
    fun `quit keeps registry rows so a reconnect can restore from them`() {
        seedTargetRows()
        resync.clear()

        sync.onTransition(SyncReason.QUIT, target)

        assertEquals(2, states.rowCount())
        assertTrue(resync.log().isEmpty())
    }

    // -- entity id reuse ---------------------------------------------------

    @Test
    fun `stale rows of an evicted identity are dropped on id reuse`() {
        // the despawn event was missed: the old owner still has a binding and
        // cached rows when a new entity claims the same entity id
        identities.register(target, 42)
        seedTargetRows()
        resync.clear()

        val registration = identities.register(bystander, 42)
        sync.onIdentityRegistered(registration)

        // everything cached about the old owner dies with its identity
        assertEquals(0, states.rowCount())
        assertNull(identities.current(target))
        assertEquals(1, identities.trackedCount())
    }

    @Test
    fun `same uuid rebind triggers a full target reapply against the new id`() {
        identities.register(target, 10)
        seedTargetRows()
        resync.clear()

        val registration = identities.register(target, 11)
        sync.onIdentityRegistered(registration)

        // rows survive (policy truth), but every viewer is corrected
        assertEquals(2, states.rowCount())
        assertEquals(2, resync.ops().count { it == "resyncTarget" })
    }

    // -- reconciliation ----------------------------------------------------

    @Test
    fun `reconcile reapplies rows drifted behind the live identity generation`() {
        identities.register(target, 5)
        states.set(viewer, target, ViewState.DISGUISED)
        sync.onTransition(SyncReason.EQUIPMENT_CHANGE, target) // pins generation 1
        resync.clear()

        // generation moved without the sync being notified (dropped event)
        identities.register(target, 6)

        val report = sync.reconcile()

        assertEquals(1, report.reapplied)
        assertEquals(1, resync.ops().count { it == "resyncTarget" })
        // a second pass with no drift does nothing: no packet spam
        resync.clear()
        val second = sync.reconcile()
        assertEquals(0, second.reapplied)
        assertTrue(resync.log().isEmpty())
    }

    @Test
    fun `reconcile reasserts dead rows so a disguise can never creep back`() {
        identities.register(target, 5)
        states.set(viewer, target, ViewState.DEAD)
        sync.onTransition(SyncReason.EQUIPMENT_CHANGE, target)
        resync.clear()

        identities.register(target, 6)

        sync.reconcile()

        assertEquals(listOf("restoreVanilla($viewer,$target)"), resync.log())
        assertEquals(ViewState.DEAD, states.get(viewer, target))
    }
}
