package kz.bejiihiu.servercore.placeholderapi

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaceholderConfigTest {

    private fun load(yaml: String): PlaceholderConfig {
        val config = YamlConfiguration()
        config.loadFromString(yaml)
        return PlaceholderConfig.load(config)
    }

    @Test
    fun `an empty config uses the documented defaults`() {
        val config = load("")

        assertEquals("unknown", config.unknownValue)
        assertEquals(600_000L, config.staleAfterMs)
        assertEquals(" (stale)", config.staleSuffix)
        assertEquals(2_000L, config.cacheTtlMs)
    }

    @Test
    fun `values are read from the placeholders section`() {
        val config = load(
            """
            placeholders:
              unknown-value: "n/a"
              stale-after-minutes: 2
              stale-suffix: " [old]"
              cache-ttl-milliseconds: 250
            """.trimIndent(),
        )

        assertEquals("n/a", config.unknownValue)
        assertEquals(120_000L, config.staleAfterMs)
        assertEquals(" [old]", config.staleSuffix)
        assertEquals(250L, config.cacheTtlMs)
    }

    @Test
    fun `a blank unknown value falls back so placeholders never render empty`() {
        assertEquals("unknown", load("placeholders:\n  unknown-value: \"  \"\n").unknownValue)
    }
}
