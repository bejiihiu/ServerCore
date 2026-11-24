package kz.bejiihiu.servercore.integration.luckperms

import java.util.UUID
import java.util.logging.Logger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LuckPermsConfigTest {

    private val logger = Logger.getLogger("luckperms-config-test")

    private fun load(yaml: String): LuckPermsConfig {
        val config = YamlConfiguration()
        config.loadFromString(yaml)
        return LuckPermsConfig.load(config, logger)
    }

    @Test
    fun `without a luckperms section the fallback stays empty and boots`() {
        val fallback = load("").fallback

        assertEquals("default", fallback.defaultGroup)
        assertTrue(fallback.groups.isEmpty())
        assertTrue(fallback.everyonePermissions.isEmpty())
        assertTrue(fallback.playerPermissions.isEmpty())
    }

    @Test
    fun `everyone permissions are granted and blank nodes are dropped`() {
        val fallback = load(
            """
            luckperms:
              fallback:
                permissions:
                  everyone: ["servercore.guest", "  "]
            """.trimIndent(),
        ).fallback

        assertEquals(listOf("servercore.guest"), fallback.everyonePermissions)
    }

    @Test
    fun `uuid keyed maps keep valid entries and ignore bad keys`() {
        val admin = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
        val helper = "11111111-2222-3333-4444-555555555555"
        val fallback = load(
            """
            luckperms:
              fallback:
                groups:
                  "$admin": admin
                  "not-a-uuid": admin
                  "$helper": "  "
                permissions:
                  players:
                    "$admin": ["servercore.admin", ""]
                    "nope": ["servercore.admin"]
            """.trimIndent(),
        ).fallback

        assertEquals(mapOf(UUID.fromString(admin) to "admin"), fallback.groups)
        assertEquals(
            mapOf(UUID.fromString(admin) to listOf("servercore.admin")),
            fallback.playerPermissions,
        )
    }

    @Test
    fun `a blank default group falls back to the documented default`() {
        val fallback = load("luckperms:\n  fallback:\n    default-group: \"  \"\n").fallback

        assertEquals("default", fallback.defaultGroup)
    }
}
