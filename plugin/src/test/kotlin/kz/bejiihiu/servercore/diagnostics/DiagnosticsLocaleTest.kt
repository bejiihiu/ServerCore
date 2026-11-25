package kz.bejiihiu.servercore.diagnostics

import kz.bejiihiu.servercore.locale.LocaleStore
import kotlin.test.Test
import kotlin.test.fail

/**
 * every key the diagnostics card can render must exist in both bundled
 * locales. the enum-driven keys are checked by walking the enums, so adding a
 * finding or a hint without copy fails the build instead of printing a raw key.
 */
class DiagnosticsLocaleTest {

    private fun bundledKeys(resource: String): Set<String> {
        val stream = javaClass.getResourceAsStream(resource)
            ?: fail("bundled resource $resource is missing from the test classpath")
        return stream.use { LocaleStore.parse(it.readAllBytes().decodeToString()).keys() }
    }

    private fun requiredKeys(): Set<String> = buildSet {
        add("diag.header")
        for (section in listOf("build", "modules", "link", "counts", "versions", "verdict")) {
            add("diag.section.$section")
        }
        for (label in listOf(
            "plugin", "jar", "server", "folia", "java", "link", "heartbeat", "outbox",
            "last-disconnect", "reconnect-attempts", "guests", "spec-sessions", "bars", "players",
        )) {
            add("diag.label.$label")
        }
        for (value in listOf("yes", "no", "connected", "disconnected", "absent", "never", "none")) {
            add("diag.value.$value")
        }
        for (version in listOf("packetevents", "luckperms", "placeholderapi", "flectonepulse")) {
            add("diag.version.$version")
        }
        for (verdict in listOf("ok", "warn", "fail")) add("diag.verdict.$verdict")

        ModuleKey.entries.forEach { add("diag.module.${it.localeKey}") }
        ModuleStatus.entries.forEach { add("diag.status.${it.localeKey}") }
        ModuleDetail.entries.forEach { add("diag.detail.${it.localeKey}") }
        FindingCode.entries.forEach { add("diag.finding.${it.localeKey}") }
        Hint.entries.forEach { add("diag.hint.${it.localeKey}") }
    }

    @Test
    fun `every diagnostic key exists in ru and en`() {
        val ru = bundledKeys("/locales/ru.yml")
        val en = bundledKeys("/locales/en.yml")
        for (key in requiredKeys()) {
            if (key !in ru) fail("locales/ru.yml is missing key: $key")
            if (key !in en) fail("locales/en.yml is missing key: $key")
        }
    }
}
