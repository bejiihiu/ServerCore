package kz.bejiihiu.servercore.spec

import java.util.logging.Logger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecConfigTest {

    private val logger = Logger.getLogger("spec-config-test")

    private fun load(yaml: String): SpecConfig {
        val config = YamlConfiguration()
        config.loadFromString(yaml)
        return SpecConfig.load(config, logger)
    }

    @Test
    fun `an empty config uses the documented permission defaults`() {
        val config = load("")

        assertTrue(config.enabled)
        assertTrue(config.liveInventory)
        assertTrue(config.permission.useLuckPerms)
        assertEquals(listOf("owner", "admin", "moderator", "helper"), config.permission.levels)
        assertEquals("servercore.spec", config.permission.specPermission)
        // the standalone node lands on the lowest level (helper = index 3)
        assertEquals(3, config.permission.fallbackLevelIndex)
    }

    @Test
    fun `spec can be disabled while the permission policy keeps its defaults`() {
        val config = load("spec:\n  enabled: false\n")

        assertFalse(config.enabled)
        assertEquals(listOf("owner", "admin", "moderator", "helper"), config.permission.levels)
    }

    @Test
    fun `levels are lowercased, trimmed, deduplicated and keep their order`() {
        val config = load(
            """
            spec:
              permission:
                levels: [" Owner ", "ADMIN", "owner", ""]
            """.trimIndent(),
        )

        assertEquals(listOf("owner", "admin"), config.permission.levels)
    }

    @Test
    fun `the named fallback level resolves to its index`() {
        val config = load(
            """
            spec:
              permission:
                levels: [owner, admin, helper]
                fallback-level: admin
            """.trimIndent(),
        )

        assertEquals(1, config.permission.fallbackLevelIndex)
    }

    @Test
    fun `an unknown fallback level degrades to the lowest level instead of failing`() {
        val config = load(
            """
            spec:
              permission:
                levels: [owner, admin, helper]
                fallback-level: moderator
            """.trimIndent(),
        )

        assertEquals(2, config.permission.fallbackLevelIndex)
    }

    @Test
    fun `a blank spec permission node falls back to the documented node`() {
        val config = load("spec:\n  permission:\n    spec-permission: \"  \"\n")

        assertEquals("servercore.spec", config.permission.specPermission)
    }

    @Test
    fun `vanilla permission checks are used when luckperms is switched off`() {
        val config = load("spec:\n  permission:\n    use-luckperms: false\n")

        assertFalse(config.permission.useLuckPerms)
    }
}
