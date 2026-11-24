package kz.bejiihiu.servercore.esp

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisibilityCacheTest {

    private val viewer = UUID.randomUUID()
    private val target = UUID.randomUUID()
    private val key = PairKey(viewer, target)

    private fun cache(ttl: Long = 4) = VisibilityCache(ttl)

    private fun entry(state: ViewState = ViewState.VISIBLE, at: Long = 0, los: Boolean? = false) =
        VisibilityCache.Entry(PairVerdict(state), at, los, losPending = false)

    @Test
    fun freshInsideTtlStaleAtExpiry() {
        val c = cache(ttl = 4)
        c.upsert(key) { entry(at = 10) }
        val stored = c[key]!!
        assertFalse(c.fresh(stored, 14))
        // ttl is inclusive-fresh: delta 3 with ttl 4 is still inside the window
        assertTrue(c.fresh(stored, 13))
    }

    @Test
    fun upsertUpdatesAtomically() {
        val c = cache()
        c.upsert(key) { entry() }
        c.upsert(key) { e -> requireNotNull(e).copy(verdict = PairVerdict(ViewState.HIDDEN)) }
        assertEquals(ViewState.HIDDEN, c[key]!!.verdict.state)
    }

    @Test
    fun removePlayerClearsBothDirections() {
        val other = UUID.randomUUID()
        val c = cache()
        c.upsert(key) { entry() }
        c.upsert(PairKey(other, viewer)) { entry() }
        c.upsert(PairKey(other, target)) { entry() }
        c.removePlayer(viewer)
        assertNull(c[key])
        assertNull(c[PairKey(other, viewer)])
        // untouched pair survives
        assertEquals(ViewState.VISIBLE, c[PairKey(other, target)]!!.verdict.state)
    }

    @Test
    fun emptyCacheAnswersNothing() {
        assertNull(cache()[key])
        assertEquals(0, cache().size())
    }
}
