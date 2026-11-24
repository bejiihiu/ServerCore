package kz.bejiihiu.servercore.spectator

import java.util.logging.Logger
import org.bukkit.configuration.file.FileConfiguration

/**
 * spec feature settings (config.yml `spec:` section). the viewer-related keys
 * belong to this package; the session side adds its own when it lands.
 */
data class SpecConfig(
    /** master switch for the whole spec feature */
    val enabled: Boolean,
    /** live read-only inventory view of the spec target */
    val liveInventory: Boolean,
    /** minimum gap between throttled diff pushes to the admin's client */
    val liveInventoryPushMs: Long,
    /** periodic full resync backstop, catches changes no event reported */
    val liveInventoryFullResyncMs: Long,
) {
    companion object {
        fun load(config: FileConfiguration): SpecConfig {
            val pushMs = config.getLong("spec.live-inventory-push-milliseconds", 500L)
            val fullResyncMs = config.getLong("spec.live-inventory-full-resync-seconds", 5L) * 1000
            require(pushMs in 50..10_000) {
                "spec.live-inventory-push-milliseconds must be between 50 and 10000, got $pushMs"
            }
            require(fullResyncMs >= pushMs) {
                "spec.live-inventory-full-resync-seconds must be at least the push interval"
            }
            return SpecConfig(
                enabled = config.getBoolean("spec.enabled", true),
                liveInventory = config.getBoolean("spec.live-inventory", true),
                liveInventoryPushMs = pushMs,
                liveInventoryFullResyncMs = fullResyncMs,
            )
        }
    }
}
