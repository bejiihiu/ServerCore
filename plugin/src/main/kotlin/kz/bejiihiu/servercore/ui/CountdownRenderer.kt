package kz.bejiihiu.servercore.ui

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.format.TextColor

/**
 * pure formatting for countdown surfaces — no scheduling, no bukkit state,
 * fully unit-testable. the runtime Countdown component consumes this.
 */
object CountdownRenderer {

    /** bossbar progress for the time left, clamped */
    fun progress(secondsLeft: Int, totalSeconds: Int): Float =
        if (totalSeconds <= 0) 0f else (secondsLeft.toFloat() / totalSeconds).coerceIn(0f, 1f)

    /**
     * bossbar color by urgency: calm yellow while there is room to breathe,
     * red once the window is mostly gone. adventure bossbar has no brass
     * tone, yellow is the closest carrier for the gold accent.
     */
    fun barColor(secondsLeft: Int, totalSeconds: Int): BossBar.Color {
        val fraction = if (totalSeconds <= 0) 0.0 else secondsLeft.toDouble() / totalSeconds
        return if (fraction > 0.25) BossBar.Color.YELLOW else BossBar.Color.RED
    }

    /** title tick color for the final five seconds: brass, then warn, then danger */
    fun tickColor(secondsLeft: Int): TextColor = when {
        secondsLeft > 3 -> Theme.GOLD
        secondsLeft > 1 -> Theme.WARN
        else -> Theme.DANGER
    }

    /** mm:ss without padding minutes — clocks read better loose */
    fun mmss(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
