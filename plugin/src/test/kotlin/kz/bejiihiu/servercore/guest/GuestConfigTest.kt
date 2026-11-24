package kz.bejiihiu.servercore.guest

import java.util.UUID
import java.util.logging.Logger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuestConfigTest {

    private val logger = Logger.getLogger("guest-config-test")

    private fun load(yaml: String): GuestConfig {
        val config = YamlConfiguration()
        config.loadFromString(yaml)
        return GuestConfig.load(config, logger)
    }

    @Test
    fun `an empty config uses the documented defaults`() {
        val config = load("")

        assertTrue(config.enabled)
        assertTrue(config.restoreOnRestart)
        assertEquals(4_000L, config.resolveTimeoutMs)
        assertEquals(3, config.sessionLimit)
        assertTrue(config.kickAfterLimit)
        assertEquals(60L, config.reminderIntervalSeconds)
        assertEquals(2L, config.gamemodeSweepSeconds)
        assertEquals(setOf("help", "rules", "link"), config.commandWhitelist)
        assertEquals("ServerCoreBot", config.botUsername)
        assertEquals("https://t.me/ServerCoreBot", config.fallbackBotUrl)
        assertEquals(90_000L, config.linkTtlMs)
        assertEquals("guest-snapshots.json", config.snapshotStoragePath)
        assertEquals("ru", config.lang)
        assertEquals(RestrictionToggles.allRestricted(), config.restrictions)
        assertTrue(config.localAccounts.isEmpty())
    }

    @Test
    fun `guest mode can be disabled without disturbing the rest of the config`() {
        val config = load("guest:\n  enabled: false\n")

        assertFalse(config.enabled)
        assertEquals(3, config.sessionLimit)
        assertEquals(RestrictionToggles.allRestricted(), config.restrictions)
    }

    @Test
    fun `out of range numbers clamp instead of aborting the boot`() {
        val config = load(
            """
            guest:
              resolve-timeout-milliseconds: 1
              session-limit: 0
              reminder-interval-seconds: 1
              gamemode-sweep-seconds: 999
              link-ttl-milliseconds: -5
            """.trimIndent(),
        )

        assertEquals(250L, config.resolveTimeoutMs)
        assertEquals(1, config.sessionLimit)
        assertEquals(10L, config.reminderIntervalSeconds)
        assertEquals(60L, config.gamemodeSweepSeconds)
        assertEquals(5_000L, config.linkTtlMs)
    }

    @Test
    fun `command whitelist entries are normalized and an empty list falls back`() {
        val custom = load("guest:\n  command-whitelist:\n    - \" /Help \"\n    - RULES\n    - \"\"\n")
        assertEquals(setOf("help", "rules"), custom.commandWhitelist)

        val empty = load("guest:\n  command-whitelist: []\n")
        assertEquals(setOf("help", "rules", "link"), empty.commandWhitelist)
    }

    @Test
    fun `local accounts keep valid uuids and drop everything else`() {
        val valid = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
        val config = load(
            """
            guest:
              local-accounts:
                "$valid": AUTHORIZED
                "not-a-uuid": banned
            """.trimIndent(),
        )

        assertEquals(mapOf(UUID.fromString(valid) to "authorized"), config.localAccounts)
    }

    @Test
    fun `restriction toggles can be lifted individually`() {
        val config = load(
            """
            guest:
              restrictions:
                chat: false
                item-use: false
            """.trimIndent(),
        )

        assertFalse(config.restrictions.chat)
        assertFalse(config.restrictions.itemUse)
        // every unspecified toggle keeps the restricted default
        assertTrue(config.restrictions.blockBreak)
        assertTrue(config.restrictions.commands)
    }

    @Test
    fun `the bot username loses the at sign and blanks fall back`() {
        assertEquals("OtherBot", load("guest:\n  bot-username: \"@OtherBot\"\n").botUsername)
        assertEquals("ServerCoreBot", load("guest:\n  bot-username: \"  \"\n").botUsername)
    }
}
