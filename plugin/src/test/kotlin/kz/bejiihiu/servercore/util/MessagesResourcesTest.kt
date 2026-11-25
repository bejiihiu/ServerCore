package kz.bejiihiu.servercore.util

import kz.bejiihiu.servercore.locale.LocaleStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * the bundled locales/ru.yml and locales/en.yml must stay key-parallel:
 * a key missing from the active language silently falls back to the base
 * layer, which leaks untranslated text to russian players. this test fails the
 * build the moment one side grows a section the other does not have.
 */
class MessagesResourcesTest {

    private fun bundledKeys(resource: String): Set<String> {
        val stream = javaClass.getResourceAsStream(resource)
            ?: fail("bundled resource $resource is missing from the test classpath")
        return stream.use { LocaleStore.parse(it.readAllBytes().decodeToString()).keys() }
    }

    @Test
    fun `ru and en locale files define the same key set`() {
        val ru = bundledKeys("/locales/ru.yml")
        val en = bundledKeys("/locales/en.yml")
        assertEquals(en, ru, "locale files drifted apart; missing keys must be translated, not dropped")
    }

    @Test
    fun `every key set used by code exists in both files`() {
        val ru = bundledKeys("/locales/ru.yml")
        val en = bundledKeys("/locales/en.yml")
        // the surfaces the plugin renders today: toolkit (tab list, welcome,
        // auth), guest lifecycle, spec (service + inventory viewer), protection
        // denial, permission debug and the placeholderapi values
        val required = listOf(
            "tablist.header",
            "tablist.footer",
            "guest.welcome.title",
            "guest.welcome.subtitle",
            "guest.welcome.card",
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
            "auth.prompt.card",
            "auth.pulse",
            "auth.window",
            "auth.expired",
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
            "countdown.tick",
            "bars.ram.title",
            "bars.tps.title",
            "bars.mspt.title",
            "bars.players.title",
            "bars.world.title",
            "bars.world.title-plain",
            "bars.world.weather-clear",
            "bars.world.weather-rain",
            "bars.world.weather-thunder",
            "bars.toggled-on",
            "bars.toggled-off",
            "bars.no-permission-other",
            "bars.player-not-found",
            "bars.players-only",
            "bars.all-empty",
            "bars.all-target",
        )
        for (key in required) {
            if (key !in ru) fail("locales/ru.yml is missing key: $key")
            if (key !in en) fail("locales/en.yml is missing key: $key")
        }
    }

    @Test
    fun `no key is shipped without a caller or a producer`() {
        // keys that only existed for a flow nobody renders are copy debt: they
        // drift, get translated twice and show up in audits as live surfaces.
        val ru = bundledKeys("/locales/ru.yml")
        val dead = listOf(
            "guest.welcome-back.title",
            "auth.waiting.actionbar",
            "auth.rejected.title",
            "auth.expired.actionbar",
        )
        for (key in dead) {
            if (key in ru) fail("locales/ru.yml still ships a key nothing renders: $key")
        }
    }

    @Test
    fun `the flat messages layer is gone`() {
        // one store only: a stale messages_*.yml in the jar would be dead copy
        // that drifts from the canonical locale files
        assertNull(javaClass.getResourceAsStream("/messages_ru.yml"), "messages_ru.yml must not ship")
        assertNull(javaClass.getResourceAsStream("/messages_en.yml"), "messages_en.yml must not ship")
    }
}
