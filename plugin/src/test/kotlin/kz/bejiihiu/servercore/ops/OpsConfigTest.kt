package kz.bejiihiu.servercore.ops

import java.time.ZoneId
import java.util.logging.Logger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpsConfigTest {

    private val logger = Logger.getLogger("ops-config-test")

    private fun load(yaml: String): OpsConfig {
        val config = YamlConfiguration()
        config.loadFromString(yaml)
        return OpsConfig.load(config, logger)
    }

    @Test
    fun `an empty config uses the documented defaults`() {
        val config = load("")

        assertTrue(config.update.enabled)
        assertEquals("bejiihiu/ServerCore", config.update.repo)
        assertEquals(30L, config.update.checkIntervalMinutes)
        assertTrue(config.update.autoRestart)
        assertEquals("GITHUB_TOKEN", config.update.githubTokenEnv)

        assertEquals("", config.restart.pterodactyl.panelUrl)
        assertEquals("", config.restart.pterodactyl.serverId)
        assertEquals("PTERO_API_KEY", config.restart.pterodactyl.apiKeyEnv)
        assertFalse(config.restart.pterodactyl.isConfigured())

        assertEquals(listOf(RestartTime(5, 0)), config.restart.dailyTimes)
        assertEquals(60, config.restart.graceSeconds)
        assertEquals("ru", config.restart.locale)
        assertEquals(ZoneId.of("Europe/Moscow"), config.restart.zone)
    }

    @Test
    fun `the whole ops stack can be switched off without disturbing defaults`() {
        val config = load("ops:\n  update:\n    enabled: false\n  restart:\n    daily-times: []\n")

        assertFalse(config.update.enabled)
        assertTrue(config.restart.dailyTimes.isEmpty())
        assertEquals(60, config.restart.graceSeconds)
    }

    @Test
    fun `an explicitly empty restart schedule means no daily restart`() {
        assertTrue(load("ops:\n  restart:\n    daily-times: []\n").restart.dailyTimes.isEmpty())
    }

    @Test
    fun `bad restart times are skipped and the valid ones survive`() {
        val config = load(
            """
            ops:
              restart:
                daily-times: ["05:00", "24:00", "noon", "17:30"]
            """.trimIndent(),
        )

        assertEquals(listOf(RestartTime(5, 0), RestartTime(17, 30)), config.restart.dailyTimes)
    }

    @Test
    fun `an unknown timezone falls back to the product default`() {
        val config = load("ops:\n  restart:\n    timezone: \"Mars/Olympus\"\n")

        assertEquals(ZoneId.of("Europe/Moscow"), config.restart.zone)
    }

    @Test
    fun `a custom timezone is honored`() {
        val config = load("ops:\n  restart:\n    timezone: \"Europe/Berlin\"\n")

        assertEquals(ZoneId.of("Europe/Berlin"), config.restart.zone)
    }

    @Test
    fun `grace seconds and the check interval clamp into the sane band`() {
        val config = load(
            """
            ops:
              update:
                check-interval-minutes: 0
              restart:
                grace-seconds: 5000
            """.trimIndent(),
        )

        assertEquals(1L, config.update.checkIntervalMinutes)
        assertEquals(600, config.restart.graceSeconds)
    }

    @Test
    fun `the restart locale coerces anything that is not en to ru`() {
        assertEquals("en", load("ops:\n  restart:\n    locale: \"en\"\n").restart.locale)
        assertEquals("ru", load("ops:\n  restart:\n    locale: \"de\"\n").restart.locale)
    }

    @Test
    fun `pterodactyl is only considered configured with all three values`() {
        val config = load(
            """
            ops:
              restart:
                pterodactyl:
                  panel-url: "https://panel.example.com"
                  server-id: "abc123"
                  api-key-env: "PTERO_API_KEY"
            """.trimIndent(),
        )

        assertTrue(config.restart.pterodactyl.isConfigured())
        assertEquals("https://panel.example.com", config.restart.pterodactyl.panelUrl)
        assertEquals("abc123", config.restart.pterodactyl.serverId)
    }
}
