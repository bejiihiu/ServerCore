package kz.bejiihiu.servercore.config

import kotlin.test.Test
import kotlin.test.assertEquals
import org.bukkit.configuration.file.YamlConfiguration

class ConfigLoaderTest {

    private fun load(yaml: String) = YamlConfiguration().also { it.loadFromString(yaml) }

    private val logger = java.util.logging.Logger.getAnonymousLogger()

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
}
