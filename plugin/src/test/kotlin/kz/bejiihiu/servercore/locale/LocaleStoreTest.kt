package kz.bejiihiu.servercore.locale

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocaleStoreTest {

    private val seed = """
        tablist:
          header: "<gold>◆</gold> <primary>закрытый сезон</primary>"
          footer: "<muted>доступ по пропуску</muted>"
        guest:
          welcome:
            title: "Гостевой пропуск"
            card: |
              <gold>гостевой пропуск</gold>
              <secondary>мир открыт для осмотра</secondary>
        meta:
          attempts: 3
          strict: true
    """.trimIndent()

    @Test
    fun `yaml flattens to dotted keys`() {
        val store = LocaleStore.parse(seed)
        assertEquals("Гостевой пропуск", store.raw("guest.welcome.title"))
        assertEquals("<muted>доступ по пропуску</muted>", store.raw("tablist.footer"))
        assertTrue(store.has("meta.attempts"))
        assertFalse(store.has("meta.nope"))
    }

    @Test
    fun `block scalars keep their newlines`() {
        val store = LocaleStore.parse(seed)
        val card = store.raw("guest.welcome.card")!!
        assertEquals(2, card.lines().size)
        assertTrue(card.contains("мир открыт для осмотра"))
    }

    @Test
    fun `scalars survive the roundtrip`() {
        val store = LocaleStore.parse(seed)
        assertEquals("3", store.raw("meta.attempts"))
        assertEquals("true", store.raw("meta.strict"))
    }

    @Test
    fun `lists join into multiline templates`() {
        val store = LocaleStore.parse("rules:\n  - первая\n  - вторая\n")
        assertEquals("первая\nвторая", store.raw("rules"))
    }

    @Test
    fun `missing key is null not throw`() {
        val store = LocaleStore.parse(seed)
        assertNull(store.raw("ghost.key"))
    }

    @Test
    fun `overlay wins key by key and keeps the rest`() {
        val store = LocaleStore.parse(seed)
        val merged = store.mergedWith(mapOf("tablist.header" to "<info>новый</info>"))
        assertEquals("<info>новый</info>", merged.raw("tablist.header"))
        assertEquals("Гостевой пропуск", merged.raw("guest.welcome.title"))
        // original store stays immutable
        assertEquals("<gold>◆</gold> <primary>закрытый сезон</primary>", store.raw("tablist.header"))
    }

    @Test
    fun `broken yaml is rejected loudly`() {
        assertThrows(Exception::class.java) { LocaleStore.parse("tablist: [unclosed") }
    }

    @Test
    fun `empty document parses to an empty store`() {
        val store = LocaleStore.parse("")
        assertTrue(store.isEmpty)
    }
}
