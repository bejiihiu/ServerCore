package kz.bejiihiu.servercore.bars

import kz.bejiihiu.servercore.ui.Theme
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor

/**
 * pure presentation of one bar reading: fill fraction, health colors and the
 * title line. no scheduling, no player state — the runtime renderer consumes
 * these answers, tests pin them.
 */
object BarPresentation {

    /** bossbar fill, clamped; a missing reading renders empty, never crashes */
    fun progress(fraction: Double?): Float =
        fraction?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f

    /**
     * adventure's bossbar color is a client-side enum, so the theme's exact
     * hex cannot ride it; green/yellow/red is the closest honest carrier for
     * the same good/medium/low semantics.
     */
    fun bossBarColor(health: BarHealth): BossBar.Color = when (health) {
        BarHealth.GOOD -> BossBar.Color.GREEN
        BarHealth.MEDIUM -> BossBar.Color.YELLOW
        BarHealth.LOW -> BossBar.Color.RED
        BarHealth.UNKNOWN -> BossBar.Color.WHITE
    }

    /** leading mark color, the actionbar's only health signal */
    fun markColor(health: BarHealth): TextColor = when (health) {
        BarHealth.GOOD -> Theme.OK
        BarHealth.MEDIUM -> Theme.WARN
        BarHealth.LOW -> Theme.DANGER
        BarHealth.UNKNOWN -> Theme.MUTED
    }

    /**
     * the full line: the brand mark plus the locale template. the bossbar keeps
     * the brass mark (its progress color already carries health), the
     * actionbar colors the mark by health because it has no fill.
     *
     * the body comes from [titles], which parses a template once instead of
     * once per tick; only the values are rendered here.
     */
    fun line(titles: BarTitleCache, bar: BarId, reading: BarReading, surface: BarSurface): Component {
        val body = titles.component(reading.titleKey ?: bar.titleKey, reading.values)
        // append copies, so the shared mark is only a base here, never mutated
        val mark = if (surface == BarSurface.BOSSBAR) BRASS_MARK else actionbarMark(reading.health)
        return mark.append(body)
    }

    private val BRASS_MARK: Component = Component.text("${Theme.MARK} ", Theme.GOLD)

    private val ACTIONBAR_MARKS: Array<Component> =
        BarHealth.entries.map { Component.text("${Theme.MARK} ", markColor(it)) }.toTypedArray()

    private fun actionbarMark(health: BarHealth): Component = ACTIONBAR_MARKS[health.ordinal]
}
