package kz.bejiihiu.servercore.spec

import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

/**
 * bukkit-visibility backend for [HiddenObserver]: hidePlayer/showPlayer are
 * per-viewer by design (exactly what the observation spec demands - hiding must
 * never be a global invisibility toggle). entity work runs on the viewer's own
 * scheduler, folia-style.
 *
 * the tab list half of "hidden from the viewer" is packet-layer territory
 * (per-viewer player info packets); when that layer lands it plugs into the
 * same [HiddenObserver] seam, this class stays as the entity-visibility floor.
 */
class BukkitHiddenObserver(private val plugin: Plugin) : HiddenObserver {

    override fun apply(viewer: UUID, target: UUID, state: VisibilityState) {
        val viewerPlayer = Bukkit.getPlayer(viewer) ?: return
        val targetPlayer = Bukkit.getPlayer(target) ?: return
        viewerPlayer.scheduler.run(
            plugin,
            { task ->
                when (state) {
                    VisibilityState.HIDDEN -> viewerPlayer.hidePlayer(plugin, targetPlayer)
                    VisibilityState.VISIBLE -> viewerPlayer.showPlayer(plugin, targetPlayer)
                }
                task.cancel()
            },
            // viewer gone mid-flight: nothing to show or hide anymore
            null,
        )
    }
}
