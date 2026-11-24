package kz.bejiihiu.servercore.ui

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin

/**
 * world-anchored floating text built on TextDisplay entities.
 *
 * folia-safe: the spawn happens on the region owning the location and the
 * removal is scheduled on the entity's own scheduler. displays are
 * non-persistent by design — a restart leaves no ghosts in the world.
 */
object Holograms {

    // slate-tinted translucent backing, matches the theme neutrals
    private val BACKING = Color.fromARGB(0x90, 0x14, 0x16, 0x18)

    /**
     * spawns a temporary floating text. safe to call from any thread; the
     * actual entity work is moved onto the owning region.
     */
    fun spawn(plugin: Plugin, at: Location, text: Component, lifetimeTicks: Long = 200L) {
        plugin.server.regionScheduler.execute(plugin, at) {
            val world = at.world ?: return@execute
            val display = world.spawn(at, TextDisplay::class.java) { d ->
                d.text(text)
                d.isPersistent = false
                d.billboard = Display.Billboard.CENTER
                d.isSeeThrough = false
                d.backgroundColor = BACKING
            }
            // retire on the thread that owns the entity, never cross-region
            display.scheduler.runDelayed(
                plugin,
                { _ -> display.remove() },
                null,
                lifetimeTicks,
            )
        }
    }
}
