package kz.bejiihiu.servercore.ui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaceholdersTest {

    @Test
    fun `substitutes named tokens`() {
        val out = Placeholders.substitute(
            "код {code}, сессия {n} из {max}",
            mapOf("code" to "123-456", "n" to "1", "max" to "3"),
        )
        assertEquals("код 123-456, сессия 1 из 3", out)
    }

    @Test
    fun `unknown tokens stay visible`() {
        val out = Placeholders.substitute("привет {missing}", mapOf("known" to "x"))
        assertEquals("привет {missing}", out)
    }

    @Test
    fun `markup in values is escaped, not executed`() {
        // the value lands as literal characters — tags are escaped, so a
        // player whose name is <red> stuff cannot paint the chat
        val component = Theme.render("<gold>{name}</gold>", mapOf("name" to "<red>хак</red>"))
        assertEquals("\\<red>хак\\</red>", Theme.plain("<gold>{name}</gold>", mapOf("name" to "<red>хак</red>")))
        assertTrue(noColorIn(component, TextColor.color(0xFF5555)))
    }

    @Test
    fun `dollar signs in values survive string replacement`() {
        // appendReplacement would eat $groups without quoteReplacement
        val out = Placeholders.substitute("{v}", mapOf("v" to "$100 and $2 specials"))
        assertEquals("$100 and $2 specials", out)
    }

    @Test
    fun `repeated token replaces every occurrence`() {
        val out = Placeholders.substitute("{x}+{x}", mapOf("x" to "1"))
        assertEquals("1+1", out)
    }

    @Test
    fun `empty values map returns template untouched`() {
        assertEquals("{a} {b}", Placeholders.substitute("{a} {b}"))
    }

    private fun noColorIn(component: Component, color: TextColor): Boolean {
        if (component.color() == color) return false
        return component.children().all { noColorIn(it, color) }
    }
}
