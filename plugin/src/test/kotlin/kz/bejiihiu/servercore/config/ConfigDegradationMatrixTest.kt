package kz.bejiihiu.servercore.config

import java.util.logging.Logger
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
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The degraded-boot matrix: every optional feature can be switched off (or
 * left without its plugin) and the config layer must still produce a complete,
 * usable snapshot. Only a broken backend block is allowed to stop the boot.
 */
class ConfigDegradationMatrixTest {

    private val logger = Logger.getLogger("config-matrix-test")

    private val backend = """
        backend:
          url: "wss://backend.example/ws"
          server-id: "game-1"
          secret: "0123456789abcdef0123456789abcdef"
    """.trimIndent()

    private data class Booted(
        val plugin: PluginConfig,
        val esp: EspConfig,
        val guest: GuestConfig,
        val packet: PacketConfig,
        val protection: ProtectionConfig,
        val spec: kz.bejiihiu.servercore.spec.SpecConfig,
        val audit: SpecAuditConfig,
        val viewer: kz.bejiihiu.servercore.spectator.SpecConfig,
        val observer: SpecObserverConfig,
        val luckPerms: LuckPermsConfig,
        val placeholders: PlaceholderConfig,
        val flectonePulse: FlectonePulseConfig,
        val ops: OpsConfig,
    )

    private fun boot(extra: String = ""): Booted {
        val config = YamlConfiguration()
        config.loadFromString(backend + "\n" + extra)
        return Booted(
            plugin = ConfigLoader.load(config, logger),
            esp = EspConfig.load(config, logger),
            guest = GuestConfig.load(config, logger),
            packet = PacketConfig.load(config),
            protection = ProtectionConfig.load(config, logger),
            spec = kz.bejiihiu.servercore.spec.SpecConfig.load(config, logger),
            audit = SpecAuditConfig.load(config, logger),
            viewer = kz.bejiihiu.servercore.spectator.SpecConfig.load(config),
            observer = SpecObserverConfig.load(config, logger),
            luckPerms = LuckPermsConfig.load(config, logger),
            placeholders = PlaceholderConfig.load(config),
            flectonePulse = FlectonePulseConfig.load(config, logger),
            ops = OpsConfig.load(config, logger),
        )
    }

    @Test
    fun `a bare backend-only config boots every subsystem on defaults`() {
        val booted = boot()

        assertTrue(booted.esp.enabled)
        assertTrue(booted.packet.enabled)
        assertTrue(booted.guest.enabled)
        assertTrue(booted.protection.enabled)
        assertTrue(booted.spec.enabled)
        assertTrue(booted.audit.enabled)
        assertTrue(booted.viewer.enabled)
        assertEquals(1.5, booted.observer.traceRadius)
        assertEquals("default", booted.luckPerms.fallback.defaultGroup)
        assertEquals("unknown", booted.placeholders.unknownValue)
        assertTrue(booted.flectonePulse.disableTg)
        assertTrue(booted.ops.update.enabled)
        assertEquals(GuestConfig.load(YamlConfiguration(), logger), booted.guest)
    }

    @Test
    fun `anti-esp off also parks the packet layer`() {
        val booted = boot("anti-esp:\n  enabled: false\n")

        assertFalse(booted.esp.enabled)
        assertFalse(booted.packet.enabled)
        // the rest of the stack is untouched
        assertTrue(booted.protection.enabled)
        assertTrue(booted.spec.enabled)
        assertTrue(booted.guest.enabled)
        assertEquals(EspConfig.defaultMobs, booted.esp.mobs)
    }

    @Test
    fun `anti-esp off keeps the disguise stack off even with packetevents present`() {
        // PacketEvents presence is a runtime check; the config contract is that
        // anti-esp.enabled false disables both the engine and the disguise read.
        val booted = boot("anti-esp:\n  enabled: false\n  disguise:\n    enabled: true\n")

        assertFalse(booted.esp.enabled)
        assertFalse(booted.packet.enabled)
        // the disguise subsection still parses, it just cannot be reached
        assertTrue(booted.esp.disguiseEnabled)
    }

    @Test
    fun `spec off disables both spec slices without breaking the observer config`() {
        val booted = boot("spec:\n  enabled: false\n")

        assertFalse(booted.spec.enabled)
        assertFalse(booted.viewer.enabled)
        // the session/permission and audit blocks still load for a later flip
        assertEquals("servercore.spec", booted.spec.permission.specPermission)
        assertTrue(booted.audit.enabled)
        assertEquals(1.5, booted.observer.traceRadius)
    }

    @Test
    fun `protection off keeps the documented command baselines`() {
        val booted = boot("protection:\n  enabled: false\n")

        assertFalse(booted.protection.enabled)
        assertEquals(ProtectionConfig.DEFAULT_BLOCKED_COMMANDS, booted.protection.blockedCommands)
        assertEquals(ProtectionConfig.DEFAULT_BLOCKED_DATAPACK_COMMANDS, booted.protection.blockedDatapackCommands)
        assertTrue(booted.guest.enabled)
    }

    @Test
    fun `guest mode off keeps every restriction toggle intact`() {
        val booted = boot("guest:\n  enabled: false\n")

        assertFalse(booted.guest.enabled)
        assertEquals(kz.bejiihiu.servercore.guest.RestrictionToggles.allRestricted(), booted.guest.restrictions)
        assertEquals(setOf("help", "rules", "link"), booted.guest.commandWhitelist)
        assertTrue(booted.protection.enabled)
    }

    @Test
    fun `no luckperms section still yields a usable static fallback`() {
        val booted = boot("luckperms: {}\n")

        assertEquals("default", booted.luckPerms.fallback.defaultGroup)
        assertTrue(booted.luckPerms.fallback.groups.isEmpty())
        assertTrue(booted.luckPerms.fallback.everyonePermissions.isEmpty())
        assertTrue(booted.spec.permission.useLuckPerms)
    }

    @Test
    fun `no placeholderapi section leaves the placeholder knobs at defaults`() {
        val booted = boot("placeholders: {}\n")

        assertEquals("unknown", booted.placeholders.unknownValue)
        assertEquals(2_000L, booted.placeholders.cacheTtlMs)
    }

    @Test
    fun `no flectonepulse section leaves telegram dedupe on`() {
        val booted = boot("flectonepulse: {}\n")

        assertTrue(booted.flectonePulse.disableTg)
        assertEquals(30_000L, booted.flectonePulse.punishmentCacheTtlMillis)
    }

    @Test
    fun `every optional feature disabled at once still boots`() {
        val booted = boot(
            """
            guest:
              enabled: false
            anti-esp:
              enabled: false
            spec:
              enabled: false
            protection:
              enabled: false
            luckperms:
              fallback:
                permissions:
                  everyone: []
            placeholders:
              unknown-value: "?"
            flectonepulse:
              disable-tg: false
            ops:
              update:
                enabled: false
              restart:
                daily-times: []
            """.trimIndent(),
        )

        assertFalse(booted.guest.enabled)
        assertFalse(booted.esp.enabled)
        assertFalse(booted.packet.enabled)
        assertFalse(booted.spec.enabled)
        assertFalse(booted.viewer.enabled)
        assertFalse(booted.protection.enabled)
        assertFalse(booted.flectonePulse.disableTg)
        assertFalse(booted.ops.update.enabled)
        assertTrue(booted.ops.restart.dailyTimes.isEmpty())
        // the transport itself is mandatory and survives all of it
        assertEquals("game-1", booted.plugin.gateway.serverId)
    }

    @Test
    fun `only a broken backend block stops the boot`() {
        val emptyUrl = YamlConfiguration().apply {
            loadFromString("backend:\n  url: \"\"\n  secret: \"0123456789abcdef0123456789abcdef\"\n")
        }
        val error = assertThrows(IllegalArgumentException::class.java) { ConfigLoader.load(emptyUrl, logger) }
        assertTrue(error.message!!.contains("backend.url"))
    }
}
