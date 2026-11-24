package kz.bejiihiu.servercore.guest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandGateTest {

    private val whitelist = setOf("help", "rules", "link")

    @Test
    fun `bare commands extract cleanly`() {
        assertEquals("help", CommandGate.extract("/help"))
        assertEquals("rules", CommandGate.extract("/rules extra args"))
        assertEquals("link", CommandGate.extract("/LINK"))
    }

    @Test
    fun `namespaced commands count as the bare command`() {
        assertEquals("help", CommandGate.extract("/minecraft:help"))
        assertEquals("help", CommandGate.extract("/bukkit:help me"))
        assertTrue(CommandGate.isAllowed("/core:link", whitelist))
    }

    @Test
    fun `non-whitelisted commands are rejected`() {
        assertFalse(CommandGate.isAllowed("/gamemode creative", whitelist))
        assertFalse(CommandGate.isAllowed("/op someone", whitelist))
        assertFalse(CommandGate.isAllowed("/tp @a 0 0 0", whitelist))
    }

    @Test
    fun `garbage input never passes`() {
        assertNull(CommandGate.extract(""))
        assertNull(CommandGate.extract("   "))
        assertNull(CommandGate.extract("/"))
        assertFalse(CommandGate.isAllowed("/", whitelist))
    }

    @Test
    fun `whitelist matching is case insensitive`() {
        assertTrue(CommandGate.isAllowed("/Help", whitelist))
        assertFalse(CommandGate.isAllowed("/helper", whitelist))
    }
}
