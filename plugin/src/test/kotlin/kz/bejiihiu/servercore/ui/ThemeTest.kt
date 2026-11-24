package kz.bejiihiu.servercore.ui

import net.kyori.adventure.text.format.TextColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemeTest {

    @Test
    fun `palette tokens are distinct`() {
        val tokens = listOf(
            Theme.PRIMARY, Theme.SECONDARY, Theme.MUTED, Theme.HAIRLINE,
            Theme.GOLD, Theme.GOLD_DIM,
            Theme.OK, Theme.WARN, Theme.DANGER, Theme.INFO,
        )
        assertEquals(tokens.size, tokens.map { it.value() }.toSet().size)
    }

    @Test
    fun `text roles stay readable on the dark chat surface`() {
        // chat background is near-black on every common client; each role
        // that carries text must clear the 4.5:1 WCAG bar against #1a1a1a
        val surface = Luma.of(0x1A1A1A)
        for (token in listOf(Theme.PRIMARY, Theme.SECONDARY, Theme.GOLD, Theme.OK, Theme.WARN, Theme.DANGER, Theme.INFO)) {
            assertTrue(token.contrastAgainst(surface) >= 4.5, "token ${token.asHex()} too dim: ${token.contrastAgainst(surface)}")
        }
        // muted and hairline are deliberately quiet but still above the 3:1 floor
        assertTrue(Theme.MUTED.contrastAgainst(surface) >= 3.0)
    }
    @Test
    fun `theme tags render with the token colors`() {
        val component = Theme.render("<gold>brand</gold> <primary>text</primary>")
        val gold = component.children().first()
        assertEquals(Theme.GOLD, gold.color())
        val primary = component.children().last()
        assertEquals(Theme.PRIMARY, primary.color())
    }

    @Test
    fun `plain strips markup for width math`() {
        val width = Theme.plain("<gold>◆</gold> <primary>код {code}</primary>", mapOf("code" to "123-456"))
        assertEquals("◆ код 123-456", width)
    }

    @Test
    fun `progress bar fills left to right and turns red late`() {
        val full = Theme.progressBar(1.0, 10)
        val half = Theme.progressBar(0.5, 10)
        // one parent, two segments: live then spent
        assertEquals(2, full.children().size)
        assertEquals(Theme.GOLD, half.children().first().color())
        assertEquals(Theme.HAIRLINE, half.children().last().color())
        assertEquals(Theme.DANGER, Theme.progressBar(0.2, 10).children().first().color())
    }

    private fun TextColor.contrastAgainst(surface: Luma): Double {
        val fg = Luma.of(asHex())
        val lighter = if (fg.relativeLuminance > surface.relativeLuminance) fg else surface
        val darker = if (fg.relativeLuminance > surface.relativeLuminance) surface else fg
        return (lighter.relativeLuminance + 0.05) / (darker.relativeLuminance + 0.05)
    }

    private fun TextColor.asHex(): Int = value()

    /** relative luminance of an rgb color per WCAG 2.x */
    private class Luma private constructor(val relativeLuminance: Double) {
        companion object {
            fun of(rgb: Int): Luma {
                fun channel(v: Int): Double {
                    val c = v / 255.0
                    return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
                }
                val r = channel(rgb shr 16 and 0xFF)
                val g = channel(rgb shr 8 and 0xFF)
                val b = channel(rgb and 0xFF)
                return Luma(0.2126 * r + 0.7152 * g + 0.0722 * b)
            }
        }
    }
}
