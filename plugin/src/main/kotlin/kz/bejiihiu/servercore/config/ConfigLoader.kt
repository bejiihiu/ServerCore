package kz.bejiihiu.servercore.config

import org.bukkit.configuration.file.FileConfiguration
import kz.bejiihiu.servercore.spectator.SpecConfig
import kz.bejiihiu.servercore.websocket.GatewayConfig

/**
 * plugin settings from config.yml. secrets stay out of the repo: config.yml
 * ships with placeholders, real values go into the server's own config.
 */
data class PluginConfig(
    val gateway: GatewayConfig,
    val spec: SpecConfig,
)

object ConfigLoader {
    fun load(config: FileConfiguration): PluginConfig {
        val url = config.getString("backend.url", "ws://127.0.0.1:8081/ws")!!.trim()
        val serverId = config.getString("backend.server-id", "game-1")!!.trim()
        val secret = config.getString("backend.secret", "")!!.trim()
        val reconnectInitial = config.getLong("backend.reconnect-initial-seconds", 1L) * 1000
        val reconnectMax = config.getLong("backend.reconnect-max-seconds", 60L) * 1000
        val heartbeat = config.getLong("backend.heartbeat-interval-seconds", 15L) * 1000

        require(url.startsWith("ws://") || url.startsWith("wss://")) { "backend.url must start with ws:// or wss://" }
        require(serverId.matches(Regex("[a-z0-9._-]+"))) { "backend.server-id must be [a-z0-9._-]+" }
        require(secret.length >= 32) { "backend.secret must be at least 32 characters" }

        return PluginConfig(
            gateway = GatewayConfig(
                url = url,
                serverId = serverId,
                secret = secret,
                reconnectInitialMs = reconnectInitial,
                reconnectMaxMs = reconnectMax,
                heartbeatIntervalMs = heartbeat,
            ),
            spec = SpecConfig.load(config),
        )
    }
}
