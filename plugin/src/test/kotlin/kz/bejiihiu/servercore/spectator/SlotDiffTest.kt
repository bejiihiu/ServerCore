package kz.bejiihiu.servercore.spectator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlotDiffTest {

    private fun item(name: String): SlotItem = SlotItem(name, 1, name.hashCode())
    private val empty: SlotItem? = null

    @Test
    fun `identical snapshots produce no changes`() {
        val a = listOf(item("stone"), empty, item("sword"))
        val b = listOf(item("stone"), empty, item("sword"))
        assertTrue(SlotDiff.changedSlots(a, b).isEmpty())
        assertFalse(SlotDiff.needsFullResync(a, b))
    }

    @Test
    fun `only the changed slots are reported`() {
        val old = listOf(item("stone"), empty, item("sword"), item("dirt"))
        val new = listOf(item("stone"), item("apple"), empty, item("dirt"))
        assertEquals(listOf(1, 2), SlotDiff.changedSlots(old, new))
    }

    @Test
    fun `empty to item and item to empty both count as changes`() {
        val old = listOf(empty, item("sword"))
        val new = listOf(item("apple"), empty)
        assertEquals(listOf(0, 1), SlotDiff.changedSlots(old, new))
    }

    @Test
    fun `count or stamp changes count as changes even with the same material`() {
        val old = listOf(SlotItem("stone", 1, 1))
        val new = listOf(SlotItem("stone", 2, 1))
        val stacked = listOf(SlotItem("stone", 1, 7))
        assertEquals(listOf(0), SlotDiff.changedSlots(old, new))
        assertEquals(listOf(0), SlotDiff.changedSlots(old, stacked))
    }

    @Test
    fun `size mismatch demands a full resync and refuses per-slot output`() {
        val old = listOf(item("stone"))
        val new = listOf(item("stone"), item("dirt"))
        assertTrue(SlotDiff.needsFullResync(old, new))
        assertThrows(IllegalArgumentException::class.java) { SlotDiff.changedSlots(old, new) }
    }
}
