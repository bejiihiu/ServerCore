package kz.bejiihiu.servercore.disguise

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.event.PacketListenerPriority
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * one-stop wiring for the disguise layer: parses config, builds the packetevents
 * renderer + suppression listener + service, registers the listener.
 *
 * the esp decision engine (esp branch) drives [applier]; until decisions
 * arrive the registry stays empty and every packet passes through untouched.
 */
class DisguiseStack private constructor(
    val applier: ViewStateApplier,
    private val listenerHandle: PacketListenerCommon,
    private val registry: DisguiseRegistry,
) {

    /** unregister the listener and drop all pair records */
    fun shutdown() {
        PacketEvents.getAPI().eventManager.unregisterListener(listenerHandle)
        registry.clear()
    }

    companion object {

        /** null when the disguise layer is off or has no mobs configured */
        fun create(plugin: Plugin, config: FileConfiguration, logger: Logger): DisguiseStack? {
            val enabled = config.getBoolean("anti-esp.enabled", true) &&
                config.getBoolean("anti-esp.disguise.enabled", true)
            if (!enabled) return null

            val mobs = DisguiseMobs.parse(config.getString("anti-esp.disguise.mobs", "") ?: "")
            if (mobs.isEmpty()) {
                logger.warning("anti-esp disguise enabled but the mob registry is empty, disguising stays off")
                return null
            }

            val registry = DisguiseRegistry()
            val captured = CapturedStateStore()
            val renderer = PacketEventsDisguiseRenderer(captured)
            val scheduler = FoliaDisguiseScheduler(plugin)
            val service = EntityDisguiseService(scheduler, renderer, registry, SyntheticEntityIds(), mobs)

            val listener = PacketEventsDisguiseListener(
                registry, captured, scheduler, renderer, onPlayerGone = service::clearFor,
            )
            val handle = PacketEvents.getAPI().eventManager.registerListener(listener, PacketListenerPriority.LOW)
            return DisguiseStack(service, handle, registry)
        }
    }
}

object DisguiseMobs {

    /** "zombie, skeleton" -> lowercase registry, minecraft: prefix tolerated, dupes dropped */
    fun parse(raw: String): List<String> =
        raw.split(',', ';')
            .map { it.trim().lowercase().removePrefix("minecraft:") }
            .filter { it.isNotEmpty() }
            .distinct()
}
