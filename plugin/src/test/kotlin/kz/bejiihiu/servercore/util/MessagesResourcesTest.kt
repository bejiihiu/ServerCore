package kz.bejiihiu.servercore.util

import kz.bejiihiu.servercore.locale.LocaleStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * the bundled messages_ru.yml and messages_en.yml must stay key-parallel:
 * a key missing from the language file silently falls back to english, which
 * leaks untranslated text to russian players. this test fails the build the
 * moment one side grows a section the other does not have.
 */
class MessagesResourcesTest {

    private fun bundledKeys(resource: String): Set<String> {
        val stream = javaClass.getResourceAsStream(resource)
            ?: fail("bundled resource $resource is missing from the test classpath")
        return stream.use { LocaleStore.parse(it.readAllBytes().decodeToString()).keys() }
    }

    @Test
    fun `ru and en message files define the same key set`() {
        val ru = bundledKeys("/messages_ru.yml")
        val en = bundledKeys("/messages_en.yml")
        assertEquals(en, ru, "message files drifted apart; missing keys must be translated, not dropped")
    }

    @Test
    fun `every key set used by code exists in both files`() {
        val ru = bundledKeys("/messages_ru.yml")
        val en = bundledKeys("/messages_en.yml")
        // spot-check the surfaces that were live at the time of the drift:
        // spec.* (spec service + inventory viewer), guest.* and protection.*
        val required = listOf(
            "spec.inventory-title",
            "spec.started",
            "spec.exited",
            "spec.aborted",
            "spec.disabled",
            "spec.no-target",
            "spec.self",
            "spec.busy-admin",
            "spec.busy-target",
            "spec.not-observing",
            "spec.denied-permission",
            "spec.denied-level",
            "guest.link-prompt",
            "guest.restriction.actionbar",
            "guest.limit.chat",
            "guest.kick.limit",
            "guest.kick.banned",
            "guest.kick.disabled",
            "protection.denied.chat",
        )
        for (key in required) {
            if (key !in ru) fail("messages_ru.yml is missing key: $key")
            if (key !in en) fail("messages_en.yml is missing key: $key")
        }
    }
}
