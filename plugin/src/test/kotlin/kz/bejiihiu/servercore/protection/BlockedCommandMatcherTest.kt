package kz.bejiihiu.servercore.protection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BlockedCommandMatcherTest {

    private val tree = object : CommandTreeSnapshot {
        override fun aliasGroups(): Collection<Set<String>> = listOf(setOf("tp", "teleport"))
    }

    private fun matcher(
        blocked: List<String> = listOf(
            "op", "deop", "fill", "clone", "setblock", "summon", "gamemode",
            "tp", "teleport", "give", "item", "execute", "effect", "attribute",
            "kill", "difficulty", "time", "weather", "gamerule", "worldborder",
            "stop", "reload",
        ),
        datapack: List<String> = listOf("function", "schedule", "datapack"),
    ): BlockedCommandMatcher = BlockedCommandMatcher(blocked, datapack) { tree }

    @Test
    fun `blocked command with arguments is denied`() {
        assertEquals(BlockedCommandMatcher.Kind.BLOCKED, matcher().match("/tp here")?.kind)
        assertEquals(BlockedCommandMatcher.Kind.BLOCKED, matcher().match("/fill 0 0 0 1 1 1 stone")?.kind)
    }

    @Test
    fun `aliases of blocked commands are denied`() {
        // only "tp" is configured, the alias group pulls "teleport" in
        val m = BlockedCommandMatcher(listOf("tp"), emptyList()) { tree }
        assertEquals(BlockedCommandMatcher.Kind.BLOCKED, m.match("/teleport ~ ~5 ~")?.kind)
        assertEquals("teleport", m.match("/teleport ~ ~5 ~")?.matched)
        assertEquals(BlockedCommandMatcher.Kind.BLOCKED, m.match("/tp here")?.kind)
    }

    @Test
    fun `namespaced forms are denied`() {
        assertEquals(BlockedCommandMatcher.Kind.BLOCKED, matcher().match("/minecraft:tp @a")?.kind)
        assertEquals(BlockedCommandMatcher.Kind.BLOCKED, matcher().match("/minecraft:summon zombie")?.kind)
    }

    @Test
    fun `matching is case insensitive`() {
        assertEquals(BlockedCommandMatcher.Kind.BLOCKED, matcher().match("/TP")?.kind)
        assertEquals(BlockedCommandMatcher.Kind.BLOCKED, matcher().match("/  SetBlock")?.kind)
    }

    @Test
    fun `similar but longer names pass`() {
        assertNull(matcher().match("/tpx here"))
        assertNull(matcher().match("/timekeeper"))
    }

    @Test
    fun `allowed commands pass`() {
        assertNull(matcher().match("/help"))
        assertNull(matcher().match("/servercore deop-all"))
        assertNull(matcher().match("/list"))
    }

    @Test
    fun `the execute chain is denied at its head`() {
        assertEquals(BlockedCommandMatcher.Kind.BLOCKED, matcher().match("/execute as @a run setblock")?.kind)
    }

    @Test
    fun `datapack invocation paths are flagged separately`() {
        val match = matcher().match("/function namespace:somefunction")
        assertNotNull(match)
        assertEquals(BlockedCommandMatcher.Kind.DATAPACK, match?.kind)
        assertEquals(BlockedCommandMatcher.Kind.DATAPACK, matcher().match("/schedule function foo:bar 10s")?.kind)
        assertEquals(BlockedCommandMatcher.Kind.DATAPACK, matcher().match("/datapack disable foo")?.kind)
    }

    @Test
    fun `garbage never matches`() {
        assertNull(matcher().match(""))
        assertNull(matcher().match("/"))
        assertNull(matcher().match("   "))
    }

    @Test
    fun `config entries are normalized before matching`() {
        val m = matcher(blocked = listOf("/MINECRAFT:Kill"))
        assertEquals(BlockedCommandMatcher.Kind.BLOCKED, m.match("/kill @e")?.kind)
    }

    @Test
    fun `an explicitly empty block list blocks nothing`() {
        assertNull(matcher(blocked = emptyList()).match("/op everyone"))
    }

    @Test
    fun `parse head tolerates namespaces and args`() {
        assertEquals("tp", BlockedCommandMatcher.parseHead("/  MineCraft:Tp here"))
        assertEquals("help", BlockedCommandMatcher.parseHead("/help"))
        assertNull(BlockedCommandMatcher.parseHead("/"))
        // messages without a leading slash never reach this matcher, the
        // parser stays lenient and just returns the head
        assertEquals("junk", BlockedCommandMatcher.parseHead("junk without slash"))
    }
}
