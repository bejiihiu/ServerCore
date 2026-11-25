package kz.bejiihiu.servercore.config

import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kz.bejiihiu.servercore.bars.BarSurface
import kz.bejiihiu.servercore.bars.BarsConfig
import kz.bejiihiu.servercore.esp.EspConfig
import kz.bejiihiu.servercore.guest.GuestConfig
import kz.bejiihiu.servercore.integration.flectonepulse.FlectonePulseConfig
import kz.bejiihiu.servercore.integration.luckperms.LuckPermsConfig
import kz.bejiihiu.servercore.ops.OpsConfig
import kz.bejiihiu.servercore.packet.PacketConfig
import kz.bejiihiu.servercore.placeholderapi.PlaceholderConfig
import kz.bejiihiu.servercore.protection.ProtectionConfig
import kz.bejiihiu.servercore.spec.SpecAuditConfig
import kz.bejiihiu.servercore.spectator.SpecObserverConfig
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/**
 * the presets under examples/plugin/ are shipped documentation. a preset that
 * carries a key nobody reads, or that silently drops a key the shipped file
 * declares, is a broken copy-paste - so every preset is diffed against the
 * real config.yml and run through every config loader.
 */
class ExamplePresetsTest {

    private val logger: Logger = Logger.getLogger("example-presets-test")

    private val presets = listOf("small-server.yml", "large-server.yml")

    @Test
    fun everyPresetDeclaresExactlyTheKeysTheShippedConfigDeclares() {
        val shipped = leaves(shippedConfig())
        presets.forEach { name ->
            val preset = leaves(preset(name))
            assertEquals(emptySet(), preset - shipped, "$name has keys config.yml does not ship")
            assertEquals(emptySet(), shipped - preset, "$name is missing keys config.yml ships")
        }
    }

    @Test
    fun everyPresetPassesEveryConfigLoader() {
        presets.forEach { name ->
            val config = preset(name)
            // ConfigLoader carries the boot-blocking checks (url, server id, secret)
            ConfigLoader.load(config, logger)
            GuestConfig.load(config, logger)
            EspConfig.load(config, logger)
            BarsConfig.load(config, logger)
            ProtectionConfig.load(config, logger)
            PacketConfig.load(config)
            kz.bejiihiu.servercore.spec.SpecConfig.load(config, logger)
            SpecAuditConfig.load(config, logger)
            kz.bejiihiu.servercore.spectator.SpecConfig.load(config)
            SpecObserverConfig.load(config, logger)
            LuckPermsConfig.load(config, logger)
            PlaceholderConfig.load(config)
            FlectonePulseConfig.load(config, logger)
            OpsConfig.load(config, logger)
        }
    }

    /**
     * the presets promise a specific shape in docs/configuration.md: guests are
     * capped at three sessions, /spec is on, protection is untouched, and the
     * large preset buys performance only from the visual layers.
     */
    @Test
    fun theSmallPresetLooksLikeASmallServer() {
        val config = preset("small-server.yml")

        assertEquals(3, GuestConfig.load(config, logger).sessionLimit)
        assertTrue(GuestConfig.load(config, logger).kickAfterLimit)
        assertTrue(kz.bejiihiu.servercore.spec.SpecConfig.load(config, logger).enabled)

        val esp = EspConfig.load(config, logger)
        assertTrue(esp.enabled)
        // a bigger interval than the shipped 10: the calm cadence is the point
        assertTrue(esp.checkIntervalTicks > 10, "small preset should relax the esp cadence")

        val bars = BarsConfig.load(config, logger)
        assertTrue(bars.enabled)
        assertEquals(20L, bars.updateIntervalTicks)
        assertEquals(BarSurface.BOSSBAR, bars.surface)

        val protection = ProtectionConfig.load(config, logger)
        assertTrue(protection.enabled)
        assertTrue(protection.enforceGamemode)
        assertTrue(protection.commandBlockNeutralize)
        assertTrue(protection.hideFromTabComplete)
    }

    @Test
    fun theLargePresetTradesVisualFreshnessForThroughput() {
        val config = preset("large-server.yml")

        val esp = EspConfig.load(config, logger)
        assertTrue(esp.enabled)
        // docs/performance.md: never below 10 ticks at four-figure populations
        assertTrue(esp.checkIntervalTicks >= 10, "large preset must keep the esp cadence at 10+")
        assertTrue(esp.cacheTtlTicks >= 40, "large preset should reuse line-of-sight longer")

        val bars = BarsConfig.load(config, logger)
        assertTrue(bars.updateIntervalTicks >= 40, "large preset should refresh bars at half rate")

        val spec = kz.bejiihiu.servercore.spec.SpecConfig.load(config, logger)
        assertTrue(spec.enabled)
        assertTrue(spec.permission.useLuckPerms)
    }

    @Test
    fun bothPresetsKeepTheSameSecurityPosture() {
        val shipped = ProtectionConfig.load(shippedConfig(), logger)
        presets.forEach { name ->
            val config = preset(name)
            val protection = ProtectionConfig.load(config, logger)
            val guest = GuestConfig.load(config, logger)

            // nothing from the shipped denial list may be dropped, and gamemode
            // enforcement stays on: these are not performance knobs
            assertEquals(shipped.blockedCommands, protection.blockedCommands, "$name weakened blocked-commands")
            assertEquals(
                shipped.blockedDatapackCommands,
                protection.blockedDatapackCommands,
                "$name weakened blocked-datapack-commands",
            )
            assertTrue(protection.enabled, "$name turned protection off")
            assertTrue(protection.enforceGamemode, "$name stopped enforcing gamemodes")
            assertTrue(protection.hideFromTabComplete, "$name exposed blocked commands in tab completion")

            // product defaults: three guest sessions, full chat isolation
            assertEquals(3, guest.sessionLimit, "$name changed the guest session budget")
            assertTrue(guest.restrictions.chat, "$name let guests talk")
            assertTrue(guest.restrictions.commands, "$name let guests run arbitrary commands")
            assertEquals(setOf("help", "rules", "link"), guest.commandWhitelist)
        }
    }

    private fun preset(name: String): YamlConfiguration = load(text("/$name"))

    /** the shipped file with the two boot-blocking placeholders filled in */
    private fun shippedConfig(): YamlConfiguration =
        load(
            text("/config.yml")
                .replace("  url: \"\"", "  url: \"wss://backend.example/ws\"")
                .replace("  secret: \"\"", "  secret: \"0123456789abcdef0123456789abcdef\""),
        )

    private fun text(resource: String): String =
        checkNotNull(javaClass.getResourceAsStream(resource)) { "$resource is missing from the test classpath" }
            .use { stream -> stream.readBytes().toString(Charsets.UTF_8) }

    private fun load(text: String): YamlConfiguration = YamlConfiguration().also { it.loadFromString(text) }

    /** leaf paths only: section names are implied by their children */
    private fun leaves(section: ConfigurationSection, prefix: String = ""): Set<String> =
        section.getKeys(false).flatMap { key ->
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            val child = section.getConfigurationSection(key)
            if (child == null) listOf(path) else leaves(child, path)
        }.toSet()
}
