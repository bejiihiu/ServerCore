package kz.bejiihiu.servercore.spec

import java.util.logging.Logger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecAuditConfigTest {

    private val logger = Logger.getLogger("spec-audit-config-test")

    private fun load(yaml: String): SpecAuditConfig {
        val config = YamlConfiguration()
        config.loadFromString(yaml)
        return SpecAuditConfig.load(config, logger)
    }

    @Test
    fun `an empty config uses the documented defaults`() {
        val config = load("")

        assertTrue(config.enabled)
        assertEquals("audit", config.directory)
        assertEquals(30, config.retentionDays)
        assertEquals(64, config.maxEntriesPerSession)
        assertEquals(1024, config.queueCapacity)
    }

    @Test
    fun `log-to-audit switches the trail off`() {
        val config = load("spec:\n  log-to-audit: false\n")

        assertFalse(config.enabled)
        // the rest of the block still loads, so a re-enable needs one flip
        assertEquals("audit", config.directory)
    }

    @Test
    fun `non-positive limits fall back to the defaults instead of aborting`() {
        val config = load(
            """
            spec:
              audit:
                retention-days: 0
                max-entries-per-session: -3
                queue-capacity: 8
            """.trimIndent(),
        )

        assertEquals(30, config.retentionDays)
        assertEquals(64, config.maxEntriesPerSession)
        assertEquals(1024, config.queueCapacity)
    }

    @Test
    fun `a custom directory is kept relative to the plugin data folder`() {
        val config = load("spec:\n  audit:\n    directory: \"logs/spec\"\n")

        assertEquals("logs/spec", config.directory)
    }

    @Test
    fun `an empty directory is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            load("spec:\n  audit:\n    directory: \"  \"\n")
        }
    }

    @Test
    fun `walking out of the data folder is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            load("spec:\n  audit:\n    directory: \"../elsewhere\"\n")
        }
        assertTrue(error.message!!.contains("inside the plugin data folder"))
    }
}
