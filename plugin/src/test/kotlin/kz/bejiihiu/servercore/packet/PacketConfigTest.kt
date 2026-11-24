package kz.bejiihiu.servercore.packet

import java.util.logging.Logger
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PacketConfigTest {

    // test logger that keeps the warning assertions checkable
    private val warnings = mutableListOf<String>()
    private val logger = object : Logger("test", null) {
        override fun warning(msg: String) { warnings.add(msg) }
    }

    private fun load(yaml: String): PacketConfig {
        val config = YamlConfiguration()
        config.loadFromString(yaml)
        return PacketConfig.load(config, logger)
    }

    @Test
    fun `missing section falls back to documented defaults`() {
        val config = load("backend:\n  url: \"\"\n")
        assertTrue(config.enabled)
        assertEquals(48, config.maxDistance)
        assertEquals(10, config.checkIntervalTicks)
        assertTrue(config.sneakHides)
        assertTrue(config.disguiseEnabled)
        assertTrue(config.mobs.isEmpty())
        assertEquals(40L, config.cacheTtlTicks)
        assertEquals(2000L, config.cacheTtlMillis)
    }

    @Test
    fun `the shipped default mob registry parses`() {
        val config = load("anti-esp:\n  disguise:\n    mobs: zombie, skeleton, spider, cow, pig, sheep\n")
        assertEquals(
            setOf(
                EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER,
                EntityType.COW, EntityType.PIG, EntityType.SHEEP,
            ),
            config.mobs.toSet(),
        )
        assertEquals(6, config.mobNames.size)
    }

    @Test
    fun `unknown mob names are dropped with a warning, known ones survive`() {
        val config = load("anti-esp:\n  disguise:\n    mobs: zombie, dragonfly, witherskeleton\n")
        assertEquals(listOf(EntityType.ZOMBIE), config.mobs)
        assertTrue(warnings.any { it.contains("dragonfly") }, "warning names the bad entry")
        assertTrue(warnings.any { it.contains("witherskeleton") })
    }

    @Test
    fun `custom values round-trip with coercion bounds`() {
        val config = load(
            """
            anti-esp:
              enabled: false
              max-distance: 500
              check-interval-ticks: 0
              sneak-hides: false
              disguise:
                enabled: false
                mobs: pig
              cache:
                ttl-ticks: 9999
            """.trimIndent(),
        )
        assertTrue(!config.enabled)
        assertEquals(128, config.maxDistance, "max-distance clamps to the upper bound")
        assertEquals(1, config.checkIntervalTicks, "check interval clamps to the lower bound")
        assertTrue(!config.sneakHides)
        assertTrue(!config.disguiseEnabled)
        assertEquals(listOf(EntityType.PIG), config.mobs)
        assertEquals(1200L, config.cacheTtlTicks, "cache ttl clamps to the upper bound")
        assertEquals(1200L * 50L, config.cacheTtlMillis)
    }

    @Test
    fun `empty mobs string means an empty registry without warnings`() {
        val config = load("anti-esp:\n  disguise:\n    mobs: \"\"\n")
        assertTrue(config.mobs.isEmpty())
        assertTrue(warnings.isEmpty())
    }
}
