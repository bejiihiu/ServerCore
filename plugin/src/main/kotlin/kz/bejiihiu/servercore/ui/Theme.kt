package kz.bejiihiu.servercore.ui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

/**
 * "obsidian and brass" — the plugin-wide design system.
 *
 * one warm slate neutral ramp for surfaces and text, one brass accent that
 * marks the brand and the one thing that matters on a screen, and semantic
 * colors toned down to live inside the same ramp. no vanilla chat colors:
 * everything players see comes from these tokens or from a locale template
 * rendered through [tags].
 */
object Theme {

    // text roles on the warm slate ramp
    val PRIMARY = TextColor.color(0xE9E4D8) // parchment, body text
    val SECONDARY = TextColor.color(0xADA79A) // supporting text
    val MUTED = TextColor.color(0x6E6A62) // meta and asides, reads quiet
    val HAIRLINE = TextColor.color(0x3C3F45) // rules and rails, never text

    // the accent: brass. the one bright thing; used sparingly, never as a wash
    val GOLD = TextColor.color(0xD9A441)
    val GOLD_DIM = TextColor.color(0x8A6A2B) // rules that belong to the brand

    // semantics, each quiet enough to sit next to the neutrals
    val OK = TextColor.color(0x7CB874)
    val WARN = TextColor.color(0xD98F4A)
    val DANGER = TextColor.color(0xD2655C)
    val INFO = TextColor.color(0x8FA8BF)

    // decorative vocabulary, fixed across every component
    const val RAIL = "┃" // left rail of system chat blocks
    const val RULE = "─" // horizontal rules
    const val TICK = "▌" // progress bars
    const val MARK = "◆" // brand mark, the signature detail
    const val PULSE_A = "▸" // actionbar pulse, filled phase
    const val PULSE_B = "▹" // actionbar pulse, hollow phase

    // chat block geometry: rule width and card width in glyph units
    const val FRAME_WIDTH = 40
    const val CARD_WIDTH = 38

    private val mini = MiniMessage.miniMessage()

    /**
     * named color tags for locale templates: <primary>, <secondary>, <muted>,
     * <hairline>, <gold>, <brand> (alias of gold), <ok>, <warn>, <danger>,
     * <info>. templates never use vanilla color names — palette changes stay
     * in this one file.
     */
    val tags: TagResolver = TagResolver.resolver(
        listOf("gold", "brand").map { TagResolver.resolver(it, Tag.styling(GOLD)) }
            + listOf(
                "primary" to PRIMARY,
                "secondary" to SECONDARY,
                "muted" to MUTED,
                "hairline" to HAIRLINE,
                "ok" to OK,
                "warn" to WARN,
                "danger" to DANGER,
                "info" to INFO,
            ).map { (name, color) -> TagResolver.resolver(name, Tag.styling(color)) }
    )

    /**
     * renders a MiniMessage template with theme tags and placeholders.
     * placeholder values are escaped first, so pushed locale content and
     * dynamic values can never inject markup.
     */
    fun render(template: String, values: Map<String, String> = emptyMap()): Component =
        mini.deserialize(Placeholders.substitute(template, values), tags)

    /** template rendered then stripped back to plain text, for width math */
    fun plain(template: String, values: Map<String, String> = emptyMap()): String =
        mini.stripTags(Placeholders.substitute(template, values), tags)

    /** a progress bar made of tick glyphs, colored by how much is left */
    fun progressBar(fraction: Double, width: Int = 20): Component {
        val filled = (fraction.coerceIn(0.0, 1.0) * width).toInt()
        val color = if (fraction >= 0.25) GOLD else DANGER
        val spent = HAIRLINE
        return Component.text()
            .append(Component.text(TICK.repeat(filled), color))
            .append(Component.text(TICK.repeat(width - filled), spent))
            .build()
    }
}
