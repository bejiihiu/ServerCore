package kz.bejiihiu.servercore.spectator

import java.util.logging.Logger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecObserverConfigTest {

    private val logger = Logger.getLogger("spec-observer-config-test")

    private fun load(yaml: String): SpecObserverConfig {
        val config = YamlConfiguration()
        config.loadFromString(yaml)
        return SpecObserverConfig.load(config, logger)
    }

    @Test
    fun `an empty config uses the documented defaults`() {
        val config = load("")

        assertEquals(1.5, config.traceRadius)
        assertTrue(config.suppressActionBar)
    }

    @Test
    fun `the hidden block can be tuned from the spec section`() {
        val config = load("spec:\n  hidden:\n    trace-radius: 3.0\n    suppress-action-bar: false\n")

        assertEquals(3.0, config.traceRadius)
        assertFalse(config.suppressActionBar)
    }

    @Test
    fun `a trace radius outside the sane band clamps instead of failing`() {
        assertEquals(0.5, load("spec:\n  hidden:\n    trace-radius: 0.1\n").traceRadius)
        assertEquals(16.0, load("spec:\n  hidden:\n    trace-radius: 999\n").traceRadius)
    }
}
