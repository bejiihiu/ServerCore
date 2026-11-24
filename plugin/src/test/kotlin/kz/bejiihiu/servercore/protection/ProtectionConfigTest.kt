package kz.bejiihiu.servercore.protection

import java.util.logging.Logger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtectionConfigTest {

    private val logger = Logger.getLogger("test")

    @Test
    fun `missing keys fall back to the built-in baseline`() {
        val config = YamlConfiguration()
        val loaded = ProtectionConfig.load(config, logger)
        assertEquals(ProtectionConfig.DEFAULT_BLOCKED_COMMANDS, loaded.blockedCommands)
        assertEquals(ProtectionConfig.DEFAULT_BLOCKED_DATAPACK_COMMANDS, loaded.blockedDatapackCommands)
        assertTrue(loaded.enabled)
        assertTrue(loaded.enforceGamemode)
        assertTrue(loaded.hideFromTabComplete)
        assertFalse(loaded.commandBlockNeutralize)
        assertEquals(600L, loaded.commandBlockAuditIntervalSeconds)
        assertEquals(30L, loaded.gamemodeSweepIntervalSeconds)
    }

    @Test
    fun `an explicitly empty block list is honored`() {
        val config = YamlConfiguration()
        config.set("protection.blocked-commands", emptyList<String>())
        val loaded = ProtectionConfig.load(config, logger)
        assertEquals(emptyList<String>(), loaded.blockedCommands)
    }

    @Test
    fun `config entries are normalized to comparable bare names`() {
        val config = YamlConfiguration()
        config.set("protection.blocked-commands", listOf("/MINECRAFT:Kill", "  Weather ", ""))
        val loaded = ProtectionConfig.load(config, logger)
        assertEquals(listOf("kill", "weather"), loaded.blockedCommands)
    }

    @Test
    fun `overrides are picked up`() {
        val config = YamlConfiguration()
        config.set("protection.enabled", false)
        config.set("protection.enforce-gamemode", false)
        config.set("protection.command-block-neutralize", true)
        config.set("protection.command-block-audit-interval-seconds", 0L)
        val loaded = ProtectionConfig.load(config, logger)
        assertFalse(loaded.enabled)
        assertFalse(loaded.enforceGamemode)
        assertTrue(loaded.commandBlockNeutralize)
        assertEquals(0L, loaded.commandBlockAuditIntervalSeconds)
    }
}
