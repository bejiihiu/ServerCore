package kz.bejiihiu.servercore.config

import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration

class ConfigLoaderTest {

    private fun load(yaml: String) = YamlConfiguration().also { it.loadFromString(yaml) }

    private val logger: Logger = Logger.getLogger("config-loader-test")

    private val nested = """
        backend:
          url: "ws://127.0.0.1:8081/ws"
          server-id: "game-1"
          secret: "0123456789abcdef0123456789abcdef"
          reconnect:
            initial-delay-seconds: 5
            max-delay-seconds: 90
    """.trimIndent()

    private val legacyFlat = """
        backend:
          url: "ws://127.0.0.1:8081/ws"
          server-id: "game-1"
          secret: "0123456789abcdef0123456789abcdef"
          reconnect-initial-seconds: 3
          reconnect-max-seconds: 77
    """.trimIndent()

    private val bothForms = """
        backend:
          url: "ws://127.0.0.1:8081/ws"
          server-id: "game-1"
          secret: "0123456789abcdef0123456789abcdef"
          reconnect-initial-seconds: 9
          reconnect-max-seconds: 99
          reconnect:
            initial-delay-seconds: 4
            max-delay-seconds: 44
    """.trimIndent()

    private val bare = """
        backend:
          url: "ws://127.0.0.1:8081/ws"
          server-id: "game-1"
          secret: "0123456789abcdef0123456789abcdef"
    """.trimIndent()

    @Test
    fun nestedReconnectKeysFromTheShippedConfigAreHonored() {
        val config = ConfigLoader.load(load(nested), logger)
        assertEquals(5_000L, config.gateway.reconnectInitialMs)
        assertEquals(90_000L, config.gateway.reconnectMaxMs)
    }

    @Test
    fun legacyFlatReconnectKeysStillWork() {
        val config = ConfigLoader.load(load(legacyFlat), logger)
        assertEquals(3_000L, config.gateway.reconnectInitialMs)
        assertEquals(77_000L, config.gateway.reconnectMaxMs)
    }

    @Test
    fun reconnectDefaultsMatchTheShippedConfig() {
        val config = ConfigLoader.load(load(bare), logger)
        // the bundled config.yml ships 2 / 120, the loader must agree with it
        assertEquals(2_000L, config.gateway.reconnectInitialMs)
        assertEquals(120_000L, config.gateway.reconnectMaxMs)
    }

    @Test
    fun nestedKeysWinOverLegacyFlatKeys() {
        val config = ConfigLoader.load(load(bothForms), logger)
        assertEquals(4_000L, config.gateway.reconnectInitialMs)
        assertEquals(44_000L, config.gateway.reconnectMaxMs)
    }

    @Test
    fun anEmptyBackendUrlIsRejectedWithAnActionableMessage() {
        val error = assertFailsWith<IllegalArgumentException> {
            ConfigLoader.load(load(nested.replace("ws://127.0.0.1:8081/ws", "")), logger)
        }
        assertTrue(error.message!!.contains("backend.url"))
    }

    @Test
    fun aNonWebsocketBackendUrlIsRejected() {
        val error = assertFailsWith<IllegalArgumentException> {
            ConfigLoader.load(load(nested.replace("ws://127.0.0.1:8081/ws", "https://example.com/ws")), logger)
        }
        assertTrue(error.message!!.contains("ws://"))
    }

    @Test
    fun aShortBackendSecretIsRejected() {
        val error = assertFailsWith<IllegalArgumentException> {
            ConfigLoader.load(load(nested.replace("0123456789abcdef0123456789abcdef", "too-short")), logger)
        }
        assertTrue(error.message!!.contains("32"))
    }

    @Test
    fun anInvalidServerIdIsRejected() {
        val error = assertFailsWith<IllegalArgumentException> {
            ConfigLoader.load(load(nested.replace("\"game-1\"", "\"GAME ONE\"")), logger)
        }
        assertTrue(error.message!!.contains("backend.server-id"))
    }

    @Test
    fun aNonPositiveHeartbeatIsRejected() {
        val yaml = bare + "\n  heartbeat-interval-seconds: 0\n"
        val error = assertFailsWith<IllegalArgumentException> { ConfigLoader.load(load(yaml), logger) }
        assertTrue(error.message!!.contains("heartbeat-interval-seconds"))
    }

    /**
     * every key the config loaders read must actually exist in the shipped
     * file, so an admin editing config.yml never has to guess a key name.
     * the static luckperms maps and guest.local-accounts are optional by
     * design and stay commented out in the shipped file.
     */
    @Test
    fun theShippedConfigDeclaresEveryKeyTheLoadersRead() {
        val shipped = shippedConfig()
        READ_KEYS.forEach { key ->
            assertTrue(shipped.contains(key), "config.yml is missing the key '$key'")
        }
        val text = shippedText()
        assertTrue(text.contains("local-accounts:"), "guest.local-accounts example missing")
        assertTrue(text.contains("groups:"), "luckperms.fallback.groups example missing")
        assertTrue(text.contains("players:"), "luckperms.fallback.permissions.players example missing")
    }

    /**
     * the shipped values are the documentation: a loader default that drifts
     * from config.yml silently changes behaviour for anyone who deletes a key.
     */
    @Test
    fun loaderDefaultsMatchTheShippedConfig() {
        val shipped = shippedConfig()
        val empty = YamlConfiguration()
        val reference = load(
            """
            backend:
              url: "wss://backend.example/ws"
              server-id: "game-1"
              secret: "0123456789abcdef0123456789abcdef"
            """.trimIndent(),
        )

        assertEquals(ConfigLoader.load(reference, logger).gateway, ConfigLoader.load(shipped, logger).gateway)
        assertEquals(EspConfig.load(empty, logger), EspConfig.load(shipped, logger))
        assertEquals(GuestConfig.load(empty, logger), GuestConfig.load(shipped, logger))
        assertEquals(PacketConfig.load(empty), PacketConfig.load(shipped))
        assertEquals(ProtectionConfig.load(empty, logger), ProtectionConfig.load(shipped, logger))
        assertEquals(
            kz.bejiihiu.servercore.spec.SpecConfig.load(empty, logger),
            kz.bejiihiu.servercore.spec.SpecConfig.load(shipped, logger),
        )
        assertEquals(SpecAuditConfig.load(empty, logger), SpecAuditConfig.load(shipped, logger))
        assertEquals(
            kz.bejiihiu.servercore.spectator.SpecConfig.load(empty),
            kz.bejiihiu.servercore.spectator.SpecConfig.load(shipped),
        )
        assertEquals(SpecObserverConfig.load(empty, logger), SpecObserverConfig.load(shipped, logger))
        assertEquals(LuckPermsConfig.load(empty, logger), LuckPermsConfig.load(shipped, logger))
        assertEquals(PlaceholderConfig.load(empty), PlaceholderConfig.load(shipped))
        assertEquals(FlectonePulseConfig.load(empty, logger), FlectonePulseConfig.load(shipped, logger))
        assertEquals(OpsConfig.load(empty, logger), OpsConfig.load(shipped, logger))
        assertEquals(BarsConfig.load(empty, logger), BarsConfig.load(shipped, logger))
    }

    private fun shippedText(): String = checkNotNull(javaClass.getResourceAsStream("/config.yml")) {
        "config.yml is missing from the plugin resources"
    }.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }

    /** the shipped file with the two required placeholders filled in */
    private fun shippedConfig(): FileConfiguration {
        val bootable = shippedText()
            .replace("  url: \"\"", "  url: \"wss://backend.example/ws\"")
            .replace("  secret: \"\"", "  secret: \"0123456789abcdef0123456789abcdef\"")
        return load(bootable)
    }

    private companion object {
        val READ_KEYS = listOf(
            "backend.url",
            "backend.server-id",
            "backend.secret",
            "backend.reconnect.initial-delay-seconds",
            "backend.reconnect.max-delay-seconds",
            "backend.heartbeat-interval-seconds",
            "backend.ack-timeout-milliseconds",
            "backend.ack-max-retries",
            "backend.liveness-timeout-milliseconds",
            "backend.dedupe-ttl-milliseconds",
            "backend.outbox-max-entries",
            "guest.enabled",
            "guest.restore-on-restart",
            "guest.resolve-timeout-milliseconds",
            "guest.session-limit",
            "guest.kick-after-limit",
            "guest.reminder-interval-seconds",
            "guest.gamemode-sweep-seconds",
            "guest.lang",
            "guest.bot-username",
            "guest.link-ttl-milliseconds",
            "guest.snapshot-storage-path",
            "guest.command-whitelist",
            "guest.restrictions.block-interaction",
            "guest.restrictions.block-break",
            "guest.restrictions.block-place",
            "guest.restrictions.item-pickup",
            "guest.restrictions.item-use",
            "guest.restrictions.container-interaction",
            "guest.restrictions.entity-damage",
            "guest.restrictions.commands",
            "guest.restrictions.gamemode-change",
            "guest.restrictions.portals",
            "guest.restrictions.inventory-edit",
            "guest.restrictions.chat",
            "anti-esp.enabled",
            "anti-esp.max-distance",
            "anti-esp.check-interval-ticks",
            "anti-esp.sneak-hides",
            "anti-esp.disguise.enabled",
            "anti-esp.disguise.mobs",
            "anti-esp.cache.ttl-ticks",
            "bars.enabled",
            "bars.update-interval-ticks",
            "bars.display",
            "bars.ram.enabled",
            "bars.ram.good",
            "bars.ram.medium",
            "bars.tps.enabled",
            "bars.tps.good",
            "bars.tps.medium",
            "bars.mspt.enabled",
            "bars.mspt.good",
            "bars.mspt.medium",
            "bars.players.enabled",
            "bars.world.enabled",
            "bars.world.show-weather",
            "spec.enabled",
            "spec.live-inventory",
            "spec.live-inventory-push-milliseconds",
            "spec.live-inventory-full-resync-seconds",
            "spec.log-to-audit",
            "spec.permission.use-luckperms",
            "spec.permission.levels",
            "spec.permission.spec-permission",
            "spec.permission.fallback-level",
            "spec.audit.directory",
            "spec.audit.retention-days",
            "spec.audit.max-entries-per-session",
            "spec.audit.queue-capacity",
            "spec.hidden.trace-radius",
            "spec.hidden.suppress-action-bar",
            "protection.enabled",
            "protection.blocked-commands",
            "protection.blocked-datapack-commands",
            "protection.enforce-gamemode",
            "protection.gamemode-sweep-interval-seconds",
            "protection.command-block-audit-interval-seconds",
            "protection.command-block-neutralize",
            "protection.hide-from-tab-complete",
            "luckperms.fallback.default-group",
            "luckperms.fallback.permissions.everyone",
            "placeholders.cache-ttl-milliseconds",
            "placeholders.unknown-value",
            "placeholders.stale-after-minutes",
            "placeholders.stale-suffix",
            "flectonepulse.disable-tg",
            "flectonepulse.punishment-cache-ttl-seconds",
            "ops.update.enabled",
            "ops.update.repo",
            "ops.update.check-interval-minutes",
            "ops.update.auto-restart",
            "ops.update.github-token-env",
            "ops.restart.pterodactyl.panel-url",
            "ops.restart.pterodactyl.server-id",
            "ops.restart.pterodactyl.api-key-env",
            "ops.restart.daily-times",
            "ops.restart.grace-seconds",
            "ops.restart.locale",
            "ops.restart.timezone",
        )
    }
}
