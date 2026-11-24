package kz.bejiihiu.servercore.integration.flectonepulse

import java.util.logging.Logger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlectonePulseConfigTest {

    private val logger = Logger.getLogger("flectonepulse-config-test")

    private fun load(yaml: String): FlectonePulseConfig {
        val config = YamlConfiguration()
        config.loadFromString(yaml)
        return FlectonePulseConfig.load(config, logger)
    }

    @Test
    fun `without the plugin section the telegram dedupe keeps its safe default`() {
        val config = load("")

        assertTrue(config.disableTg)
        assertEquals(30_000L, config.punishmentCacheTtlMillis)
    }

    @Test
    fun `the dedupe can be switched off`() {
        assertFalse(load("flectonepulse:\n  disable-tg: false\n").disableTg)
    }

    @Test
    fun `the punishment cache ttl clamps into the sane band`() {
        assertEquals(1_000L, load("flectonepulse:\n  punishment-cache-ttl-seconds: 0\n").punishmentCacheTtlMillis)
        assertEquals(
            3_600_000L,
            load("flectonepulse:\n  punishment-cache-ttl-seconds: 999_999\n").punishmentCacheTtlMillis,
        )
    }
}
