package kz.bejiihiu.servercore.esp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.bukkit.configuration.file.YamlConfiguration

class EspConfigTest {

    private fun load(yaml: String) = YamlConfiguration().also { it.loadFromString(yaml) }

    @Test
    fun defaultsComeFromTheBundledConfig() {
        val config = EspConfig.load(
            load(
                """
                anti-esp:
                  enabled: true
                  max-distance: 48
                  check-interval-ticks: 10
                  sneak-hides: true
                  disguise:
                    enabled: true
                    mobs: zombie, skeleton, spider, cow, pig, sheep
                  cache:
                    ttl-ticks: 40
                """.trimIndent(),
            ),
            java.util.logging.Logger.getAnonymousLogger(),
        )

        assertTrue(config.enabled)
        assertEquals(48.0, config.maxDistance)
        assertEquals(10L, config.checkIntervalTicks)
        assertTrue(config.sneakHides)
        assertTrue(config.disguiseEnabled)
        assertEquals(EspConfig.defaultMobs, config.mobs)
        assertEquals(40L, config.cacheTtlTicks)
    }

    @Test
    fun missingSectionFallsBackToSaneDefaults() {
        val config = EspConfig.load(YamlConfiguration(), java.util.logging.Logger.getAnonymousLogger())
        assertTrue(config.enabled)
        assertEquals(48.0, config.maxDistance)
        assertEquals(EspConfig.defaultMobs, config.mobs)
    }

    @Test
    fun mobNamesNormalizeAndUnknownOnesDrop() {
        val config = EspConfig.load(
            load(
                """
                anti-esp:
                  disguise:
                    enabled: true
                    mobs: iron golem, wither-skeleton, not-a-mob, cow
                """.trimIndent(),
            ),
            java.util.logging.Logger.getAnonymousLogger(),
        )
        assertEquals(listOf("IRON_GOLEM", "WITHER_SKELETON", "COW"), config.mobs)
    }

    @Test
    fun emptyMobListFallsBackToDefaults() {
        val config = EspConfig.load(
            load("anti-esp:\n  disguise:\n    mobs: \"\"\n"),
            java.util.logging.Logger.getAnonymousLogger(),
        )
        assertEquals(EspConfig.defaultMobs, config.mobs)
    }

    @Test
    fun valuesClampIntoSaneBounds() {
        val config = EspConfig.load(
            load(
                """
                anti-esp:
                  max-distance: 9999
                  check-interval-ticks: 0
                  cache:
                    ttl-ticks: 100000
                """.trimIndent(),
            ),
            java.util.logging.Logger.getAnonymousLogger(),
        )
        assertEquals(128.0, config.maxDistance)
        assertEquals(1L, config.checkIntervalTicks)
        assertEquals(2000L, config.cacheTtlTicks)
    }

    @Test
    fun rulesDeriveFromConfig() {
        val config = EspConfig.load(
            load("anti-esp:\n  max-distance: 24\n"),
            java.util.logging.Logger.getAnonymousLogger(),
        )
        val rules = config.rules()
        assertEquals(24.0 * 24.0, rules.maxDistanceSquared)
        assertEquals(config.mobs, rules.mobs)
    }
}
