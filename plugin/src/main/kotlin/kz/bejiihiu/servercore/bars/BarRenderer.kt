package kz.bejiihiu.servercore.bars

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kz.bejiihiu.servercore.locale.LocaleService
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.entity.Player

/**
 * draws bars onto a player's screen.
 *
 * one bossbar instance per player is reused for whichever bar is live, so
 * switching ram -> tps renames the same bar instead of stacking a second one.
 * everything here assumes the caller is already on the player's entity
 * scheduler — folia sends player packets from the owning region thread only.
 */
class BarRenderer(private val locale: LocaleService) {

    private val bossBars = ConcurrentHashMap<UUID, BossBar>()

    fun render(player: Player, bar: BarId, reading: BarReading, surface: BarSurface) {
        val line = BarPresentation.line(locale, bar, reading, surface)
        val progress = BarPresentation.progress(reading.fraction)
        val color = BarPresentation.bossBarColor(reading.health)

        if (surface == BarSurface.ACTIONBAR) {
            // a bossbar left over from a previous surface would sit there forever
            dropBossBar(player)
            player.sendActionBar(line)
            return
        }

        val bossBar = bossBars.computeIfAbsent(player.uniqueId) {
            BossBar.bossBar(line, progress, color, OVERLAY)
        }
        bossBar.name(line)
        bossBar.progress(progress)
        bossBar.color(color)
        player.showBossBar(bossBar)
    }

    /** hides and forgets the bar; call on the owning thread */
    fun clear(player: Player) {
        dropBossBar(player)
    }

    /** drops the instance without touching the player (a quit has no safe thread) */
    fun forget(uuid: UUID) {
        bossBars.remove(uuid)
    }

    private fun dropBossBar(player: Player) {
        bossBars.remove(player.uniqueId)?.let { player.hideBossBar(it) }
    }

    private companion object {
        // same overlay purpur uses for its bars
        val OVERLAY: BossBar.Overlay = BossBar.Overlay.NOTCHED_20
    }
}
