package kz.bejiihiu.servercore.spectator

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecConfigTest {

    private fun load(yaml: String): SpecConfig {
        val config = YamlConfiguration()
        config.loadFromString(yaml)
        return SpecConfig.load(config)
    }

    @Test
    fun `an empty config uses the documented defaults`() {
        val config = load("")

        assertTrue(config.enabled)
        assertTrue(config.liveInventory)
        assertEquals(500L, config.liveInventoryPushMs)
        assertEquals(5_000L, config.liveInventoryFullResyncMs)
    }

    @Test
    fun `spec can be disabled without touching the inventory toggles`() {
        val config = load("spec:\n  enabled: false\n  live-inventory: false\n")

        assertFalse(config.enabled)
        assertFalse(config.liveInventory)
        assertEquals(500L, config.liveInventoryPushMs)
    }

    @Test
    fun `a push interval outside the allowed band is rejected with the key name`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            load("spec:\n  live-inventory-push-milliseconds: 10\n")
        }
        assertTrue(error.message!!.contains("spec.live-inventory-push-milliseconds"))
    }

    @Test
    fun `a full resync shorter than the push interval is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            load("spec:\n  live-inventory-push-milliseconds: 900\n  live-inventory-full-resync-seconds: 0\n")
        }
        assertTrue(error.message!!.contains("full-resync"))
    }
}
