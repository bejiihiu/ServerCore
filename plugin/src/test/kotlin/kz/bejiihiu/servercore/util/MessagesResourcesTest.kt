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
        // the surfaces the plugin renders today: guest lifecycle, auth payoff,
        // spec (service + inventory viewer), protection denial, permission debug
        val required = listOf(
            "guest.welcome.title",
            "guest.welcome.subtitle",
            "guest.welcome-back.subtitle",
            "guest.link-prompt",
            "guest.restriction.actionbar",
            "guest.reminder.actionbar",
            "guest.limit.warning.title",
            "guest.limit.warning.subtitle",
            "guest.limit.last-session",
            "guest.limit.chat",
            "guest.mute-notice",
            "guest.kick.limit",
            "guest.kick.banned",
            "guest.kick.disabled",
            "auth.approved.title",
            "auth.approved.subtitle",
            "account.revoked",
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
            "protection.denied.chat",
            "luckperms.groups.usage",
            "luckperms.groups.entry",
            "luckperms.groups.primary-mark",
            "placeholders.status.guest",
            "placeholders.application.voting",
            "placeholders.last-seen.minutes",
        )
        for (key in required) {
            if (key !in ru) fail("messages_ru.yml is missing key: $key")
            if (key !in en) fail("messages_en.yml is missing key: $key")
        }
    }

    @Test
    fun `no key is shipped without a caller or a producer`() {
        // keys that only existed for a flow nobody renders are copy debt: they
        // drift, get translated twice and show up in audits as live surfaces.
        // the auth lifecycle lives in the canonical locale seed now.
        val ru = bundledKeys("/messages_ru.yml")
        val dead = listOf(
            "guest.welcome-back.title",
            "auth.waiting.actionbar",
            "auth.rejected.title",
            "auth.expired.actionbar",
        )
        for (key in dead) {
            if (key in ru) fail("messages_ru.yml still ships a key nothing renders: $key")
        }
    }
}
