package kz.bejiihiu.servercore.packet

import org.bukkit.configuration.file.FileConfiguration

/**
 * config for the packet layer. keys live in the existing anti-esp section and
 * are kept exactly as they are — no renames, the section predates this module.
 *
 * only what the packet layer itself needs lives here; the engine-facing knobs
 * (distance, interval, sneak, disguise) are read by the esp module's own
 * config, and the cache ttl key belongs to it too — the applied-state store
 * must never expire rows on a timer (an expired HIDDEN row fails open), so
 * the packet layer does not consume it.
 */
data class PacketConfig(
    val enabled: Boolean,
) {
    companion object {
        fun load(file: FileConfiguration): PacketConfig {
            val section = file.getConfigurationSection("anti-esp")

            return PacketConfig(
                enabled = section?.getBoolean("enabled", true) ?: true,
            )
        }
    }
}
