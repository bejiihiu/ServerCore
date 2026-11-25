package kz.bejiihiu.servercore

import java.io.File
import kz.bejiihiu.servercore.boot.AdminCommandInstaller
import kz.bejiihiu.servercore.boot.BarsInstaller
import kz.bejiihiu.servercore.boot.ConfigInstaller
import kz.bejiihiu.servercore.boot.DiagnosticsInstaller
import kz.bejiihiu.servercore.boot.EspInstaller
import kz.bejiihiu.servercore.boot.FlectonePulseInstaller
import kz.bejiihiu.servercore.boot.GatewayInstaller
import kz.bejiihiu.servercore.boot.GuestInstaller
import kz.bejiihiu.servercore.boot.I18nInstaller
import kz.bejiihiu.servercore.boot.IntegrationInstaller
import kz.bejiihiu.servercore.boot.OpsInstaller
import kz.bejiihiu.servercore.boot.PacketInstaller
import kz.bejiihiu.servercore.boot.PapiInstaller
import kz.bejiihiu.servercore.boot.PluginContext
import kz.bejiihiu.servercore.boot.ProtectionInstaller
import kz.bejiihiu.servercore.boot.SpecInstaller
import kz.bejiihiu.servercore.boot.SpecObserverInstaller
import kz.bejiihiu.servercore.boot.SpecReportingInstaller
import kz.bejiihiu.servercore.boot.SyncInstaller
import kz.bejiihiu.servercore.boot.UiInstaller
import kz.bejiihiu.servercore.boot.ViewerInstaller
import kz.bejiihiu.servercore.diagnostics.DiagnosticsSources
import kz.bejiihiu.servercore.packet.PacketConfig
import org.bukkit.plugin.java.JavaPlugin

/**
 * Single entry point, folia-only. All scheduling goes through folia's
 * async/entity schedulers, never the bukkit one.
 *
 * Lifecycle only: this class owns onLoad/onEnable/onDisable and the boot
 * order. Every subsystem lives in its own installer under [kz.bejiihiu.servercore.boot],
 * so a feature touches one file instead of this one.
 */
class ServerCorePlugin : JavaPlugin() {

    /** The plugin jar itself. [JavaPlugin.getFile] stays protected in the api. */
    val jarFile: File get() = file

    private var ctx: PluginContext? = null
    private var ops: OpsInstaller? = null
    private var gateway: GatewayInstaller? = null
    private var specReporting: SpecReportingInstaller? = null
    private var integrations: IntegrationInstaller? = null
    private var guest: GuestInstaller? = null
    private var packet: PacketInstaller? = null
    private var sync: SyncInstaller? = null
    private var viewer: ViewerInstaller? = null
    private var observer: SpecObserverInstaller? = null
    private var protection: ProtectionInstaller? = null
    private var spec: SpecInstaller? = null
    private var esp: EspInstaller? = null
    private var papi: PapiInstaller? = null
    private var bars: BarsInstaller? = null
    private var diagnostics: DiagnosticsInstaller? = null

    override fun onLoad() {
        // packetevents is a server-installed plugin we attach to, not one we
        // bundle — pre-flight just checks it is on the server, the shared
        // instance boots with its own plugin lifecycle
        saveDefaultConfig()
        val packetConfig = PacketConfig.load(config)
        if (packetConfig.enabled && server.pluginManager.getPlugin("PacketEvents") == null) {
            logger.warning("packetevents plugin not found on this server, packet layer will idle")
        }
    }

    override fun onEnable() {
        saveDefaultConfig()

        val ctx = PluginContext.create(this)
        this.ctx = ctx

        val flectonePulse = FlectonePulseInstaller(ctx)
        flectonePulse.install()

        val ops = OpsInstaller(ctx, flectonePulse)
        ops.install()
        this.ops = ops

        // bad backend config is a boot blocker. it is checked here, after the
        // ops stack, so onDisable still unwinds updater and restarts cleanly
        if (!ConfigInstaller(ctx).install()) {
            server.pluginManager.disablePlugin(this)
            return
        }

        val gateway = GatewayInstaller(ctx, ops)
        gateway.install()
        this.gateway = gateway

        val specReporting = SpecReportingInstaller(ctx, gateway)
        specReporting.install()
        this.specReporting = specReporting

        // guest config + message layer: everything from luckperms on renders
        // through i18n, so it comes up before the rest of the graph
        I18nInstaller(ctx).install()

        val integrations = IntegrationInstaller(ctx)
        integrations.install()
        this.integrations = integrations

        val guest = GuestInstaller(ctx, flectonePulse)
        guest.install()
        this.guest = guest

        val packet = PacketInstaller(ctx)
        packet.install()
        this.packet = packet

        val sync = SyncInstaller(ctx, packet)
        sync.install()
        this.sync = sync

        val viewer = ViewerInstaller(ctx)
        viewer.install()
        this.viewer = viewer

        val observer = SpecObserverInstaller(ctx, packet, sync)
        observer.install()
        this.observer = observer

        val protection = ProtectionInstaller(ctx, guest)
        protection.install()
        this.protection = protection

        val spec = SpecInstaller(ctx, protection, observer, viewer, specReporting)
        spec.install()
        this.spec = spec

        val esp = EspInstaller(ctx, guest, observer, sync)
        esp.install()
        this.esp = esp

        // one /servercore tree for the admin subcommands, registered from the
        // commands lifecycle event once the permission source and op lockdown
        // are in place; diag resolves its handler lazily, because the
        // self-check is wired at the very end of the boot
        AdminCommandInstaller(ctx, integrations, protection, { diagnostics?.service }).install()

        // deferred wiring, in the order the flow needs it: session recovery and
        // join listeners first, then the ui surface, then the transport goes
        // live, then integrations that read the live hub, and finally the bus
        // announces the boot
        guest.start()

        val ui = UiInstaller(ctx)
        ui.install()

        // info bars are another player-facing surface: the locale layer and the
        // player schedulers are all they need, so they come up with the ui and
        // before the transport — a bar defect must never delay the ws link
        val bars = BarsInstaller(ctx)
        bars.install()
        this.bars = bars

        gateway.start()

        val papi = PapiInstaller(ctx, guest, bars)
        papi.install()
        this.papi = papi

        // the self-check reads every installer, so it is wired last; the
        // command tree already points at it through a lazy supplier
        val diagnostics = DiagnosticsInstaller(
            ctx,
            DiagnosticsSources(
                guest = guest,
                packet = packet,
                esp = esp,
                protection = protection,
                spec = spec,
                bars = bars,
                papi = papi,
                gateway = gateway,
                flectonePulse = flectonePulse,
            ),
        )
        diagnostics.install()
        this.diagnostics = diagnostics

        ops.announce()

        logger.info(
            "servercore ${pluginMeta.version} enabled " +
                "(guest mode: ${if (ctx.guestConfig.enabled) "on" else "off"}, " +
                "flectonepulse: ${flectonePulse.bridge.currentState().name.lowercase()})",
        )
    }

    override fun onDisable() {
        // dependency order, not the reverse of boot: every modified client view
        // goes back to vanilla truth before anything else shuts down, sessions
        // unwind while the packet events pipeline is still attached, and the io
        // pool outlives every store that flushes through it
        papi?.stop()
        // bars first among the surfaces: cancel the refresh tasks, clear the
        // bossbars while players are still connected, and flush the toggle
        // store while the io pool is still up
        bars?.stop()
        sync?.stop()
        spec?.stop()
        observer?.stop()
        packet?.stopService()
        gateway?.stop()
        esp?.stop()
        ops?.stop()
        viewer?.stop()
        packet?.stopDisguise()
        protection?.stop()
        integrations?.stop()
        guest?.stop()
        specReporting?.stop()
        ctx?.io?.shutdown()
        logger.info("servercore ${pluginMeta.version} disabled, bye")
    }
}
