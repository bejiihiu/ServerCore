package kz.bejiihiu.servercore.protection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AliasExpansionTest {

    private class FakeTree(private vararg val groups: Set<String>) : CommandTreeSnapshot {
        override fun aliasGroups(): Collection<Set<String>> = groups.toList()
    }

    @Test
    fun `seed expands to every label of its group`() {
        val expanded = AliasExpander.expand(listOf("tp"), FakeTree(setOf("tp", "teleport")))
        assertEquals(setOf("tp", "teleport"), expanded)
    }

    @Test
    fun `expansion works from any member of the group`() {
        val expanded = AliasExpander.expand(listOf("teleport"), FakeTree(setOf("tp", "teleport")))
        assertEquals(setOf("tp", "teleport"), expanded)
    }

    @Test
    fun `groups chain through shared names`() {
        val tree = FakeTree(setOf("a", "b"), setOf("b", "c"))
        val expanded = AliasExpander.expand(listOf("a"), tree)
        assertEquals(setOf("a", "b", "c"), expanded)
    }

    @Test
    fun `names without a group stay as they are`() {
        val expanded = AliasExpander.expand(listOf("help"), FakeTree(setOf("tp", "teleport")))
        assertEquals(setOf("help"), expanded)
    }

    @Test
    fun `matching is case insensitive`() {
        val expanded = AliasExpander.expand(listOf("TP"), FakeTree(setOf("Teleport", "TP")))
        assertEquals(setOf("tp", "teleport"), expanded)
    }

    @Test
    fun `multiple seeds union their groups`() {
        val tree = FakeTree(setOf("tp", "teleport"), setOf("w", "msg"))
        val expanded = AliasExpander.expand(listOf("tp", "msg"), tree)
        assertEquals(setOf("tp", "teleport", "w", "msg"), expanded)
    }

    @Test
    fun `empty tree leaves seeds untouched`() {
        val expanded = AliasExpander.expand(listOf("fill", "clone"), FakeTree())
        assertEquals(setOf("fill", "clone"), expanded)
    }

    @Test
    fun `expansion never shrinks the seed set`() {
        val expanded = AliasExpander.expand(listOf("op", "deop"), FakeTree(setOf("op")))
        assertTrue("deop" in expanded && "op" in expanded)
    }
}
