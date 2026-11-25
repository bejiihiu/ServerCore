package kz.bejiihiu.servercore.bars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kz.bejiihiu.servercore.protection.Permissions

/**
 * command surface: the purpur-compatible names, the argument parser and the
 * permission nodes the luckperms setup has to grant.
 */
class BarCommandsTest {

    @Test
    fun `bar command names match the purpur muscle memory`() {
        assertEquals("rambar", BarId.RAM.commandName)
        assertEquals("tpsbar", BarId.TPS.commandName)
        assertEquals("msptbar", BarId.MSPT.commandName)
        assertEquals("playersbar", BarId.PLAYERS.commandName)
        assertEquals("worldbar", BarId.WORLD.commandName)
    }

    @Test
    fun `permissions carry the bar and the other node`() {
        assertEquals(Permissions.BARS_RAM, BarId.RAM.permission)
        assertEquals(Permissions.BARS_RAM_OTHER, BarId.RAM.otherPermission)
        assertEquals("servercore.bars.ram", BarId.RAM.permission)
        assertEquals("servercore.bars.ram.other", BarId.RAM.otherPermission)
        assertEquals("servercore.bars.tps", BarId.TPS.permission)
        assertEquals("servercore.bars.tps.other", BarId.TPS.otherPermission)
        assertEquals("servercore.bars.mspt", BarId.MSPT.permission)
        assertEquals("servercore.bars.mspt.other", BarId.MSPT.otherPermission)
        assertEquals("servercore.bars.players", BarId.PLAYERS.permission)
        assertEquals("servercore.bars.players.other", BarId.PLAYERS.otherPermission)
        assertEquals("servercore.bars.world", BarId.WORLD.permission)
        assertEquals("servercore.bars.world.other", BarId.WORLD.otherPermission)
    }

    @Test
    fun `the target parser treats all as a literal, not a player`() {
        assertEquals(BarTarget.Self, BarTarget.parse(null))
        assertEquals(BarTarget.All, BarTarget.parse("all"))
        assertEquals(BarTarget.All, BarTarget.parse("ALL"))
        assertEquals(BarTarget.Named("Steve"), BarTarget.parse(" Steve "))
    }

    @Test
    fun `a target that looks like the literal falls back to a name`() {
        assertTrue(BarTarget.parse("alll") is BarTarget.Named)
        assertEquals(BarTarget.Named("AllPlayers"), BarTarget.parse("AllPlayers"))
    }
}
