package kz.bejiihiu.servercore.diagnostics

import kz.bejiihiu.servercore.ui.Theme
import kz.bejiihiu.servercore.util.I18n
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.Locale

/**
 * renders a [DiagnosticsReport] into the chat card the operator reads.
 *
 * copy lives in the locale files; this class only lays it out. one line list
 * feeds both surfaces: the player card (component lines behind the theme rail)
 * and the console output (the same lines stripped to plain text), so the two
 * can never drift.
 */
class DiagnosticsRenderer(private val i18n: I18n) {

    fun card(report: DiagnosticsReport): List<Component> = lines(report)

    fun console(report: DiagnosticsReport): List<String> =
        lines(report).map { PLAIN.serialize(it) }

    private fun lines(report: DiagnosticsReport): List<Component> = buildList {
        add(i18n.component("diag.header", mapOf("version" to report.build.pluginVersion)))

        add(i18n.component("diag.section.build"))
        add(row("diag.label.plugin", text(report.build.pluginVersion)))
        add(row("diag.label.jar", text(report.build.jarName)))
        add(row("diag.label.server", text(report.build.serverVersion)))
        add(row("diag.label.folia", i18n.component(if (report.build.folia) "diag.value.yes" else "diag.value.no")))
        add(row("diag.label.java", text(report.build.javaVersion)))

        add(i18n.component("diag.section.modules"))
        for (module in report.modules) add(moduleLine(module))

        add(i18n.component("diag.section.link"))
        add(
            row(
                "diag.label.link",
                i18n.component(if (report.link.connected) "diag.value.connected" else "diag.value.disconnected"),
            ),
        )
        add(row("diag.label.heartbeat", heartbeat(report.link.lastHeartbeatAgeMs)))
        add(row("diag.label.outbox", text(report.link.outboxSize.toString())))
        add(
            row(
                "diag.label.last-disconnect",
                report.link.lastDisconnectReason?.let(::text) ?: i18n.component("diag.value.none"),
            ),
        )
        add(row("diag.label.reconnect-attempts", text(report.link.reconnectAttempts.toString())))

        add(i18n.component("diag.section.counts"))
        add(row("diag.label.guests", text(report.counts.guests.toString())))
        add(row("diag.label.spec-sessions", text(report.counts.specSessions.toString())))
        add(row("diag.label.bars", text(report.counts.bars.toString())))
        add(row("diag.label.players", text(report.counts.onlinePlayers.toString())))

        add(i18n.component("diag.section.versions"))
        add(row("diag.version.packetevents", version(report.versions.packetEvents)))
        add(row("diag.version.luckperms", version(report.versions.luckPerms)))
        add(row("diag.version.placeholderapi", version(report.versions.placeholderApi)))
        add(row("diag.version.flectonepulse", version(report.versions.flectonePulse)))

        add(i18n.component("diag.section.verdict"))
        add(verdictLine(report))
        for (finding in report.findings) {
            val color = if (finding.code.severity == Severity.FAIL) Theme.DANGER else Theme.WARN
            add(
                Component.text("▸ ", color).append(
                    i18n.component("diag.finding.${finding.code.localeKey}", finding.values).colorIfAbsent(color),
                ),
            )
        }
        report.hints.forEachIndexed { index, hint ->
            add(
                Component.text("${index + 1}. ", Theme.MUTED)
                    .append(i18n.component("diag.hint.${hint.localeKey}")),
            )
        }
    }

    private fun verdictLine(report: DiagnosticsReport): Component = when (report.verdict) {
        Verdict.OK -> i18n.component("diag.verdict.ok")
        Verdict.WARN -> i18n.component(
            "diag.verdict.warn",
            mapOf("count" to report.findingCount(Severity.WARN).toString()),
        )
        Verdict.FAIL -> i18n.component(
            "diag.verdict.fail",
            mapOf("count" to report.findingCount(Severity.FAIL).toString()),
        )
    }

    private fun moduleLine(state: ModuleState): Component {
        val out = Component.text()
        out.append(Component.text(i18n.text("diag.module.${state.module.localeKey}") + " ", Theme.SECONDARY))
        out.append(Component.text("· ", Theme.MUTED))
        out.append(i18n.component("diag.status.${state.status.localeKey}"))
        state.detail?.let { detail ->
            out.append(Component.text(" · ", Theme.MUTED))
            out.append(Component.text(i18n.text("diag.detail.${detail.localeKey}"), Theme.MUTED))
        }
        return out.build()
    }

    private fun row(labelKey: String, value: Component): Component =
        Component.text(i18n.text(labelKey) + " ", Theme.SECONDARY)
            .append(Component.text("· ", Theme.MUTED))
            .append(value)

    private fun text(value: String): Component = Component.text(value, Theme.PRIMARY)

    private fun version(value: String?): Component =
        if (value == null) i18n.component("diag.value.absent") else text(value)

    private fun heartbeat(ageMs: Long?): Component = when {
        ageMs == null -> i18n.component("diag.value.never")
        ageMs < 1_000 -> text("$ageMs ms")
        else -> text(String.format(Locale.ROOT, "%.1f s", ageMs / 1000.0))
    }

    private companion object {
        val PLAIN: PlainTextComponentSerializer = PlainTextComponentSerializer.plainText()
    }
}
