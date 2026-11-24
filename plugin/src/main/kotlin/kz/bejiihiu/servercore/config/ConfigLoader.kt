package kz.bejiihiu.servercore.config

import kz.bejiihiu.servercore.spec.SpecAuditConfig
import kz.bejiihiu.servercore.spectator.SpecConfig
import org.bukkit.configuration.file.FileConfiguration
import kz.bejiihiu.servercore.websocket.GatewayConfig

/**
 * plugin settings from config.yml. secrets stay out of the repo: config.yml
 * ships with placeholders, real values go into the server's own config.
 */
data class PluginConfig(
    val gateway: GatewayConfig,
    val spec: SpecConfig,
    val specAudit: SpecAuditConfig,
)

object ConfigLoader {
    fun load(config: FileConfiguration, logger: java.util.logging.Logger): PluginConfig {
        val url = config.getString("backend.url", "ws://127.0.0.1:8081/ws")!!.trim()
        val serverId = config.getString("backend.server-id", "game-1")!!.trim()
        val secret = config.getString("backend.secret", "")!!.trim()
        val reconnectInitial = config.getLong("backend.reconnect-initial-seconds", 1L) * 1000
        val reconnectMax = config.getLong("backend.reconnect-max-seconds", 60L) * 1000
        val heartbeat = config.getLong("backend.heartbeat-interval-seconds", 15L) * 1000
        val ackTimeout = config.getLong("backend.ack-timeout-milliseconds", 10_000L)
        val ackMaxRetries = config.getInt("backend.ack-max-retries", 2)
        val livenessTimeout = config.getLong("backend.liveness-timeout-milliseconds", 45_000L)
        val dedupeTtl = config.getLong("backend.dedupe-ttl-milliseconds", 600_000L)
        val outboxMax = config.getInt("backend.outbox-max-entries", 500)

        require(url.startsWith("ws://") || url.startsWith("wss://")) { "backend.url must start with ws:// or wss://" }
        require(serverId.matches(Regex("[a-z0-9._-]+"))) { "backend.server-id must be [a-z0-9._-]+" }
        require(secret.length >= 32) { "backend.secret must be at least 32 characters" }
        require(ackTimeout > 0) { "backend.ack-timeout-milliseconds must be positive" }
        require(ackMaxRetries >= 0) { "backend.ack-max-retries must not be negative" }
        require(livenessTimeout > 0) { "backend.liveness-timeout-milliseconds must be positive" }
        require(dedupeTtl > 0) { "backend.dedupe-ttl-milliseconds must be positive" }
        require(outboxMax > 0) { "backend.outbox-max-entries must be positive" }

        return PluginConfig(
            gateway = GatewayConfig(
                url = url,
                serverId = serverId,
                secret = secret,
                reconnectInitialMs = reconnectInitial,
                reconnectMaxMs = reconnectMax,
                heartbeatIntervalMs = heartbeat,
                ackTimeoutMs = ackTimeout,
                ackMaxRetries = ackMaxRetries,
                livenessTimeoutMs = livenessTimeout,
                dedupeTtlMs = dedupeTtl,
                outboxMaxEntries = outboxMax,
            ),
            spec = SpecConfig.load(config),
            specAudit = SpecAuditConfig.load(config, logger),
        )
    }
}
