package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.diagnostics.DiagnosticsProbe
import kz.bejiihiu.servercore.diagnostics.DiagnosticsRenderer
import kz.bejiihiu.servercore.diagnostics.DiagnosticsService
import kz.bejiihiu.servercore.diagnostics.DiagnosticsSources

/**
 * /servercore diag wiring.
 *
 * built after every other installer so the probe sees the final runtime
 * objects. the command tree is attached much earlier on purpose: it resolves
 * the handler through a supplier, so a boot that never reaches this installer
 * answers with the startup line instead of a stack trace.
 */
class DiagnosticsInstaller(
    private val ctx: PluginContext,
    private val sources: DiagnosticsSources,
) {

    /** null until install ran; the command supplier reads it lazily */
    var service: DiagnosticsService? = null
        private set

    fun install() {
        service = DiagnosticsService(
            ctx = ctx,
            probe = DiagnosticsProbe(ctx, sources),
            renderer = DiagnosticsRenderer(ctx.i18n),
        )
    }
}
