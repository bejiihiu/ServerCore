package kz.bejiihiu.servercore.packet

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PacketConfigTest {

    private fun load(yaml: String): PacketConfig {
        val config = YamlConfiguration()
        config.loadFromString(yaml)
        return PacketConfig.load(config)
    }

    @Test
    fun `missing section falls back to documented defaults`() {
        val config = load("backend:\n  url: \"\"\n")
        assertTrue(config.enabled)
    }

    @Test
    fun `custom values round-trip`() {
        val config = load(
            """
            anti-esp:
              enabled: false
              disguise:
                mobs: pig
            """.trimIndent(),
        )
        assertTrue(!config.enabled)
    }

    @Test
    fun `enabled defaults to true when only the section exists`() {
        val config = load("anti-esp:\n  max-distance: 48\n")
        assertTrue(config.enabled)
    }
}
