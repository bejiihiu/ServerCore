package kz.bejiihiu.servercore.packet

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

/**
 * answers exactly one question at boot: can we attach to packetevents, yes or no.
 * everything downstream treats a "no" as a permanent no-op, never a crash.
 *
 * lifecycle note: we depend on the server-installed packetevents plugin
 * (paper-plugin.yml declares it required + join-classpath), so we do NOT
 * build our own instance — packetevents owns it, other plugins share it.
 */
class PacketCompatibilityService {

    @Volatile private var cachedPlugin: Plugin? = null
    @Volatile private var cachedApi: PacketEventsAPI<*>? = null

    /** re-probe once at boot; results are cached, no bukkit access from packet threads */
    fun refresh() {
        cachedPlugin = Bukkit.getPluginManager().getPlugin("PacketEvents")
        cachedApi = runCatching { PacketEvents.getAPI() }.getOrNull()
    }

    fun packetEventsPlugin(): Plugin? = cachedPlugin

    fun api(): PacketEventsAPI<*>? = cachedApi

    /** plugin present, enabled, and its api instance is live */
    fun isAvailable(): Boolean {
        val plugin = cachedPlugin ?: return false
        return plugin.isEnabled && cachedApi != null
    }

    /** one-line boot report, for the log */
    fun describe(): String {
        val plugin = cachedPlugin
        val version = (plugin as? org.bukkit.plugin.java.JavaPlugin)?.pluginMeta?.version ?: "unknown"
        return "packetevents $version (${if (isAvailable()) "attached" else "unavailable"})"
    }
}
