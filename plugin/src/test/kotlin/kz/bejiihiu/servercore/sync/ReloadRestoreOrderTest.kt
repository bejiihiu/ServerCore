package kz.bejiihiu.servercore.sync

import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * reload safety: on disable every modified client view must go back to
 * vanilla truth in a deterministic order, the registry must end up empty
 * (rebuild-from-scratch on the next enable), and the double-load guard must
 * refuse a second live sync instance.
 */
class ReloadRestoreOrderTest {

    private val v1 = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val v2 = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val t1 = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val t2 = UUID.fromString("00000000-0000-0000-0000-00000000000d")
    private val t3 = UUID.fromString("00000000-0000-0000-0000-00000000000e")

    private var sync: PacketStateSync? = null

    @AfterEach
    fun tearDown() {
        sync?.release()
        sync = null
    }

    private fun newSync(): PacketStateSync =
        PacketStateSync.create(InMemoryViewStateRegistry(), RecordingResync(), EntityIdentityTracker())!!
            .also { sync = it }

    @Test
    fun `disable restores every pair to vanilla in deterministic order`() {
        val states = InMemoryViewStateRegistry()
        val resync = RecordingResync()
        val live = PacketStateSync.create(states, resync, EntityIdentityTracker())!!
            .also { sync = it }

        // deliberately unordered seed across viewers, including a DEAD pin
        states.set(v2, t1, ViewState.DISGUISED)
        states.set(v1, t3, ViewState.HIDDEN)
        states.set(v1, t2, ViewState.VISIBLE)
        states.set(v2, t2, ViewState.DEAD)

        val report = live.restoreAllForReload()

        // sorted by viewer, then target: no map-iteration luck involved
        assertEquals(
            listOf(
                ViewStateRow(v1, t2, ViewState.VISIBLE),
                ViewStateRow(v1, t3, ViewState.HIDDEN),
                ViewStateRow(v2, t1, ViewState.DISGUISED),
                ViewStateRow(v2, t2, ViewState.DEAD),
            ),
            report.restored,
        )
        assertEquals(
            listOf(
                "restoreVanilla($v1,$t2)",
                "restoreVanilla($v1,$t3)",
                "restoreVanilla($v2,$t1)",
                "restoreVanilla($v2,$t2)",
            ),
            resync.log(),
        )
        // nothing survives disable: the next enable rebuilds from scratch
        assertEquals(0, states.rowCount())
        assertTrue(states.allRows().isEmpty())
    }

    @Test
    fun `double load is refused until the live instance releases`() {
        val first = newSync()
        assertNotNull(first)

        // reload tools can spin a second instance while the first is alive
        val second = PacketStateSync.create(
            InMemoryViewStateRegistry(),
            RecordingResync(),
            EntityIdentityTracker(),
        )
        assertNull(second)

        // disable path releases the claim...
        first.release()
        sync = null
        // ...and the next enable can claim it again
        val third = newSync()
        assertNotNull(third)
    }

    @Test
    fun `plugin reload transition runs the full restore pass`() {
        val states = InMemoryViewStateRegistry()
        val resync = RecordingResync()
        val live = PacketStateSync.create(states, resync, EntityIdentityTracker())!!
            .also { sync = it }

        states.set(v1, t1, ViewState.DISGUISED)
        states.set(v2, t1, ViewState.HIDDEN)

        live.onTransition(SyncReason.PLUGIN_RELOAD, v1)

        assertEquals(
            listOf(
                "restoreVanilla($v1,$t1)",
                "restoreVanilla($v2,$t1)",
            ),
            resync.log(),
        )
        assertEquals(0, states.rowCount())
    }
}
