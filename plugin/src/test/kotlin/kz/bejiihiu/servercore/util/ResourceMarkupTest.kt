package kz.bejiihiu.servercore.util

import kz.bejiihiu.servercore.locale.LocaleStore
import kz.bejiihiu.servercore.ui.Theme
import net.kyori.adventure.text.minimessage.MiniMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * markup hygiene for every bundled locale resource.
 *
 * a locale file is pushed over the wire and rendered straight into a player's
 * client, so a typo here is a visible bug: an unclosed <gold> paints the rest
 * of the server, a vanilla <red> silently breaks the palette, a stray < prints
 * a tag into chat. strict miniMessage parsing catches all three — it refuses
 * unknown tags and refuses to leave child-allowing tags open.
 */
class ResourceMarkupTest {

    private val resources = listOf("/messages_ru.yml", "/messages_en.yml", "/locales/ru.yml")

    // vanilla color names. <gold> is deliberately absent: it is our own token.
    private val vanillaColors = listOf(
        "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
        "dark_gray", "dark_grey", "blue", "green", "aqua", "red", "light_purple",
        "yellow", "gray", "grey", "white", "reset",
    )

    private val strict = MiniMessage.builder().strict(true).tags(Theme.tags).build()

    /** the client wraps chat at ~53 glyphs; keep a couple of cells of slack */
    private val chatWidth = 50

    private fun entries(resource: String): Map<String, String> {
        val stream = javaClass.getResourceAsStream(resource)
            ?: fail("bundled resource $resource is missing from the test classpath")
        val store = stream.use { LocaleStore.parse(it.readAllBytes().decodeToString()) }
        return store.keys().associateWith { store.raw(it)!! }
    }

    @Test
    fun `every template parses as strict miniMessage`() {
        for (resource in resources) {
            for ((key, template) in entries(resource)) {
                try {
                    strict.deserialize(template)
                } catch (error: Exception) {
                    fail("$resource -> $key does not parse: ${error.message} in [$template]")
                }
            }
        }
    }

    @Test
    fun `no template leaks an unparsed tag into the rendered text`() {
        for (resource in resources) {
            for ((key, template) in entries(resource)) {
                val plain = MiniMessage.miniMessage().stripTags(template, Theme.tags)
                assertFalse(plain.contains("<"), "$resource -> $key renders a literal '<': [$template]")
                assertFalse(plain.contains(">"), "$resource -> $key renders a literal '>': [$template]")
            }
        }
    }

    @Test
    fun `no template uses a vanilla color name`() {
        for (resource in resources) {
            for ((key, template) in entries(resource)) {
                for (color in vanillaColors) {
                    assertFalse(
                        Regex("<\\s*/?\\s*$color\\b").containsMatchIn(template),
                        "$resource -> $key uses vanilla color <$color>, theme tokens only: [$template]",
                    )
                }
                assertFalse(template.contains('\u00a7'), "$resource -> $key carries a legacy section sign")
            }
        }
    }

    @Test
    fun `no empty tag pairs and no doubled spaces`() {
        for (resource in resources) {
            for ((key, template) in entries(resource)) {
                assertFalse(
                    Regex("<([a-z_]+)></\\1>").containsMatchIn(template),
                    "$resource -> $key has an empty tag pair: [$template]",
                )
                for (line in template.lines()) {
                    assertFalse(line.contains("  "), "$resource -> $key has a doubled space: [$line]")
                    assertTrue(line == line.trimEnd(), "$resource -> $key has trailing whitespace: [$line]")
                }
            }
        }
    }

    @Test
    fun `every template is visible text, not markup only`() {
        for (resource in resources) {
            for ((key, template) in entries(resource)) {
                val plain = Theme.plain(template).trim()
                assertTrue(plain.isNotEmpty(), "$resource -> $key renders to nothing")
            }
        }
    }

    @Test
    fun `chat surfaces fit the chat box without wrapping mid-rail`() {
        // the client wraps chat at roughly 53 glyphs, and a railed line that
        // wraps leaves the rail dangling on the next row. plain chat replies
        // (spec, groups) wrap fine and are not held to the frame width.
        val chatKeys = listOf(
            "guest.link-prompt",
            "guest.limit.chat",
            "guest.limit.last-session",
            "guest.mute-notice",
            "guest.welcome.card",
            "auth.prompt.card",
            "protection.denied.chat",
        )
        val alreadyChecked = mutableSetOf<String>()
        val tooWide = mutableListOf<String>()
        for (resource in resources) {
            for ((key, template) in entries(resource)) {
                if (key !in chatKeys) continue
                alreadyChecked += key
                for (line in template.lines()) {
                    val width = Theme.plain(line).length
                    if (width > chatWidth) tooWide += "$resource -> $key is $width glyphs: [$line]"
                }
            }
        }
        assertTrue(tooWide.isEmpty(), "these lines wrap mid-rail:\n" + tooWide.joinToString("\n"))
        assertEquals(chatKeys.toSet(), alreadyChecked, "a chat key in the list is missing from the resources")
    }

    @Test
    fun `placeholderapi values stay plain text`() {
        // these are consumed as legacy strings by placeholderapi, not rendered
        // as miniMessage: a tag here would print itself into a scoreboard
        val entries = entries("/messages_ru.yml") + entries("/messages_en.yml")
        for ((key, template) in entries) {
            if (!key.startsWith("placeholders.")) continue
            assertFalse(template.contains("<"), "placeholders value $key must stay plain text: [$template]")
        }
    }

    @Test
    fun `the guards above actually reject broken markup`() {
        // a green suite that cannot fail is worse than no suite: prove each
        // check catches the failure mode it exists for
        assertFailsWith<Exception>("unclosed tag must not parse") {
            strict.deserialize("<gold>never closed")
        }
        // strict mode lets unknown names through as literal text; the rendered
        // text check is what catches a typo like <secondar>
        val unknownTag = MiniMessage.miniMessage().stripTags("<secondar>typo</secondar>")
        assertTrue(unknownTag.contains("<"), "the leak check must see an unknown tag: [$unknownTag]")
        assertTrue(
            vanillaColors.any { Regex("<\\s*/?\\s*$it\\b").containsMatchIn("<red>x</red>") },
            "the vanilla color list must match a vanilla tag",
        )
        assertTrue(
            Regex("<([a-z_]+)></\\1>").containsMatchIn("<gold></gold>"),
            "the empty pair check must match an empty pair",
        )
    }
}
