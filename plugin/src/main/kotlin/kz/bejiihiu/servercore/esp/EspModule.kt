package kz.bejiihiu.servercore.esp

import java.util.UUID
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * assembles the engine graph and owns its lifecycle. the plugin gets one of
 * these when anti-esp is enabled; the packetevents module calls
 * [engine.attachApplier] with the real view-state writer once it is up.
 */
class EspModule private constructor(
    val engine: VisibilityEngine,
    private val plugin: JavaPlugin,
) {

    private val tracker = object : Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        fun onJoin(event: PlayerJoinEvent) = engine.track(event.player)

        @EventHandler(priority = EventPriority.MONITOR)
        fun onQuit(event: PlayerQuitEvent) = engine.untrack(event.player.uniqueId)
    }

    fun start() {
        engine.start()
        // reload / soft-enable mid-session: the join listener below only
        // covers players joining after this point, everyone already online
        // is tracked here or the anti-esp would silently stay off for them
        plugin.server.onlinePlayers.forEach { engine.track(it) }
        plugin.server.pluginManager.registerEvents(tracker, plugin)
    }

    fun stop() {
        engine.stop()
        org.bukkit.event.HandlerList.unregisterAll(tracker)
    }

    companion object {
        fun create(
            plugin: JavaPlugin,
            config: EspConfig,
            guests: (UUID) -> Boolean,
            spec: SpecObserverSource = SpecObserverSource.NONE,
        ): EspModule {
            val rules = config.rules()
            val engine = VisibilityEngine(
                plugin = plugin,
                config = config,
                rules = rules,
                guestLookup = guests,
                specLookup = spec::isObserving,
            )
            return EspModule(engine, plugin)
        }
    }
}
