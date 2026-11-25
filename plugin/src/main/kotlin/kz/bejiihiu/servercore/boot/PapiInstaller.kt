package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.placeholderapi.PlaceholderConfig
import kz.bejiihiu.servercore.placeholderapi.PlaceholderResolver
import kz.bejiihiu.servercore.placeholderapi.PlaceholderText
import kz.bejiihiu.servercore.placeholderapi.ServerCoreExpansion
import kz.bejiihiu.servercore.placeholderapi.ServerStateCache
import kz.bejiihiu.servercore.placeholderapi.StatePlayerListener
import kz.bejiihiu.servercore.placeholderapi.StateWsListener

/**
 * PlaceholderAPI integration. Papi is a soft dependency: not installed means
 * the whole state cache stays cold and the plugin runs exactly as before, so
 * this is a plain presence check, not a hard requirement.
 *
 * Bar values ride the same expansion through [BarsInstaller.placeholderValues]:
 * the metrics already render into a snapshot layer, and placeholders read that
 * layer instead of asking a metric for a number on somebody else's thread.
 */
class PapiInstaller(
    private val ctx: PluginContext,
    private val guest: GuestInstaller,
    private val bars: BarsInstaller,
) {

    private var expansion: ServerCoreExpansion? = null

    fun install() {
        if (ctx.plugin.server.pluginManager.getPlugin("PlaceholderAPI") == null) {
            ctx.logger.info("placeholderapi not installed, servercore placeholders disabled")
            return
        }
        val stateCache = ServerStateCache()
        // backend facts: auth verdicts, application verdicts, account deletion
        ctx.backendGateway.addListener(StateWsListener(stateCache))
        // local facts: join/quit timestamps, live session time, cache seeding
        ctx.plugin.server.pluginManager.registerEvents(
            StatePlayerListener(stateCache, guest.accountsCache),
            ctx.plugin,
        )
        val expansion = ServerCoreExpansion(
            plugin = ctx.plugin,
            resolver = PlaceholderResolver(
                provider = stateCache,
                config = PlaceholderConfig.load(ctx.plugin.config),
                strings = PlaceholderText.fromI18n(ctx.i18n),
                bars = bars.placeholderValues(),
            ),
        )
        val registered = runCatching { expansion.register() }
            .onFailure { ctx.logger.warning("placeholderapi expansion registration threw: ${it.message}") }
            .getOrDefault(false)
        if (registered) {
            this.expansion = expansion
            ctx.logger.info(
                "placeholderapi expansion registered: " +
                    "%servercore_<status|authenticated|application_status|telegram_linked|last_seen|session_duration" +
                    "|bar_ram|bar_tps|bar_mspt|bar_players|bar_net>%",
            )
        } else {
            ctx.logger.warning("placeholderapi expansion registration failed, placeholders stay off")
        }
    }

    fun stop() {
        expansion?.let { expansion ->
            runCatching { expansion.unregister() }
                .onFailure { ctx.logger.warning("placeholderapi expansion unregister failed: ${it.message}") }
        }
        expansion = null
    }
}
