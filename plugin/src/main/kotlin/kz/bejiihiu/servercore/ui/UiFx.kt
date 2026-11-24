package kz.bejiihiu.servercore.ui

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.time.Duration

/**
 * low-level rendering surfaces: title, actionbar, chat frame, tab list.
 * these know nothing about locale keys — components above them feed text in,
 * they only know geometry, timing and the theme.
 */
object UiFx {

    // title timing conventions, in milliseconds. title is a state-change
    // surface: quick fade in, long enough to read, fast out
    val TITLE_FADE_IN: Duration = Duration.ofMillis(100)
    val TITLE_FADE_OUT: Duration = Duration.ofMillis(300)

    /**
     * shows a title/subtitle pair. title reads as a headline (theme colors
     * already applied by the caller), subtitle as the supporting line.
     */
    fun title(player: Player, title: Component, subtitle: Component, stayMs: Long = 2600) {
        player.showTitle(
            net.kyori.adventure.title.Title.title(
                title,
                subtitle,
                net.kyori.adventure.title.Title.Times.times(TITLE_FADE_IN, Duration.ofMillis(stayMs), TITLE_FADE_OUT),
            ),
        )
    }

    /**
     * one transient actionbar line. the client holds it for about three
     * seconds; continuous state should re-send on its own schedule instead
     * of asking for a longer persistence.
     */
    fun actionBar(player: Player, line: Component) {
        player.sendActionBar(line)
    }

    /** tab list header and footer, ambient identity surface */
    fun tabList(player: Player, header: Component, footer: Component) {
        player.sendPlayerListHeaderAndFooter(header, footer)
    }

    /**
     * a chat block: hairline rule on top, every content line behind the
     * brand rail, rule on the bottom. content lines arrive pre-rendered;
     * the rail and rules come from the theme, not from locale files.
     */
    fun frame(lines: List<Component>, width: Int = Theme.FRAME_WIDTH): Component {
        val out = Component.text()
        out.append(rule(width))
        for (line in lines) {
            out.append(Component.newline())
            out.append(Component.text("${Theme.RAIL} ", Theme.HAIRLINE))
            out.append(line)
        }
        out.append(Component.newline())
        out.append(rule(width))
        return out.build()
    }

    /** sends a pre-built frame as one chat message so it cannot interleave */
    fun sendFrame(player: Player, lines: List<Component>, width: Int = Theme.FRAME_WIDTH) {
        player.sendMessage(frame(lines, width))
    }

    private fun rule(width: Int): Component =
        Component.text(Theme.RULE.repeat(width), Theme.GOLD_DIM)
}
