package kz.bejiihiu.servercore.spectator

import java.util.logging.Logger
import org.bukkit.configuration.file.FileConfiguration

/**
 * config slice for the hidden observer packet layer, read from the existing
 * spec section. keys are additive on purpose — the session side of the spec
 * module reads the same section, no renames.
 */
data class SpecObserverConfig(
    /** proximity radius for attributing a positioned trace to the admin, blocks */
    val traceRadius: Double,
    /**
     * drop the whole actionbar channel toward an observed target for the
     * duration of a session. attribution of an actionbar to admin actions is
     * impossible at packet level (the packet carries no position and no
     * origin), so this is all-or-nothing per session: on = maximum stealth,
     * off = the target keeps every normal server feedback line.
     */
    val suppressActionBar: Boolean,
) {
    companion object {
        fun load(file: FileConfiguration, logger: Logger): SpecObserverConfig {
            val hidden = file.getConfigurationSection("spec")?.getConfigurationSection("hidden")
            return SpecObserverConfig(
                traceRadius = hidden?.getDouble("trace-radius", 1.5)?.coerceIn(0.5, 16.0) ?: 1.5,
                suppressActionBar = hidden?.getBoolean("suppress-action-bar", true) ?: true,
            )
        }
    }
}
