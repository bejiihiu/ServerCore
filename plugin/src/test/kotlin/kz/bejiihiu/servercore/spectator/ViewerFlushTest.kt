package kz.bejiihiu.servercore.spectator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * the live viewer's cadence without a server: the flush loop only gathers
 * facts, every rule (keep-alive, backstop, throttle retry, full-vs-diff) is
 * decided here.
 */
class ViewerFlushTest {

    private fun probe(
        closed: Boolean = false,
        adminPresent: Boolean = true,
        targetPresent: Boolean = true,
        keepAliveDue: Boolean = false,
        sessionAlive: Boolean = true,
        backstopDue: Boolean = false,
        needsFull: Boolean = false,
        dirty: Boolean = false,
        throttleReady: Boolean = true,
    ) = ViewerFlushProbe(
        closed = closed,
        adminPresent = adminPresent,
        targetPresent = targetPresent,
        keepAliveDue = keepAliveDue,
        sessionAlive = sessionAlive,
        backstopDue = backstopDue,
        needsFull = needsFull,
        dirty = dirty,
        throttleReady = throttleReady,
    )

    @Test
    fun `a closed viewer is dropped without asking anything else`() {
        val step = ViewerFlush.decide(probe(closed = true, adminPresent = false, dirty = true))
        assertEquals(ViewerFlushStep.Drop, step)
    }

    @Test
    fun `either party leaving closes the viewer`() {
        assertEquals(
            ViewerFlushStep.Close(ViewerFlush.PLAYERS_LEFT),
            ViewerFlush.decide(probe(adminPresent = false)),
        )
        assertEquals(
            ViewerFlushStep.Close(ViewerFlush.PLAYERS_LEFT),
            ViewerFlush.decide(probe(targetPresent = false)),
        )
    }

    @Test
    fun `the keep-alive closes a viewer whose session moved on`() {
        val step = ViewerFlush.decide(probe(keepAliveDue = true, sessionAlive = false))
        assertEquals(ViewerFlushStep.Close(ViewerFlush.SESSION_MOVED), step)
    }

    @Test
    fun `an off-cycle tick does not probe the session`() {
        // the keep-alive runs every few cycles; between them a stale session
        // read must not tear the view down
        val step = ViewerFlush.decide(probe(keepAliveDue = false, sessionAlive = false, needsFull = true))
        assertEquals(ViewerFlushStep.FullPush, step)
    }

    @Test
    fun `the safety backstop forces a full resync`() {
        val step = ViewerFlush.decide(probe(backstopDue = true, dirty = true))
        assertEquals(ViewerFlushStep.FullPush, step)
    }

    @Test
    fun `a pending full push wins over a diff`() {
        val step = ViewerFlush.decide(probe(needsFull = true, dirty = true))
        assertEquals(ViewerFlushStep.FullPush, step)
    }

    @Test
    fun `an unchanged viewer is idle`() {
        assertEquals(ViewerFlushStep.Idle, ViewerFlush.decide(probe(dirty = false, needsFull = false)))
    }

    @Test
    fun `a dirty viewer inside the throttle window stays pending`() {
        val step = ViewerFlush.decide(probe(dirty = true, throttleReady = false))
        assertEquals(ViewerFlushStep.Idle, step)
    }

    @Test
    fun `a dirty viewer with an open throttle diff-pushes`() {
        val step = ViewerFlush.decide(probe(dirty = true, throttleReady = true))
        assertEquals(ViewerFlushStep.DiffPush, step)
    }

    // ---- what the diff push sends ----

    private val old = listOf("a", "b", "c", "d")

    @Test
    fun `an identical snapshot sends nothing`() {
        val push = ViewerPushPlan.forSnapshot(old, old.toList(), windowKnown = true, stateKnown = true)
        assertEquals(ViewerPush.Nothing, push)
    }

    @Test
    fun `only the changed slots are addressed`() {
        val new = listOf("a", "B", "c", "d")

        val push = ViewerPushPlan.forSnapshot(old, new, windowKnown = true, stateKnown = true)

        assertEquals(ViewerPush.Slots(listOf(1)), push)
    }

    @Test
    fun `a moved layout cannot be patched per slot`() {
        val new = listOf("a", "b", "c", "d", "e")

        val push = ViewerPushPlan.forSnapshot(old, new, windowKnown = true, stateKnown = true)

        assertEquals(ViewerPush.NeedsFull, push)
    }

    @Test
    fun `a window identity that is not on the wire yet forces the full packet`() {
        val new = listOf("A", "b", "c", "d")

        assertEquals(ViewerPush.NeedsFull, ViewerPushPlan.forSnapshot(old, new, windowKnown = false, stateKnown = true))
        assertEquals(ViewerPush.NeedsFull, ViewerPushPlan.forSnapshot(old, new, windowKnown = true, stateKnown = false))
    }

    @Test
    fun `an unchanged snapshot needs no window identity at all`() {
        // no packet goes out, so a missing window id is not a reason to rebuild
        val push = ViewerPushPlan.forSnapshot(old, old.toList(), windowKnown = false, stateKnown = false)
        assertEquals(ViewerPush.Nothing, push)
    }
}
