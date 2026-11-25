package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.esp.EspConfig
import kz.bejiihiu.servercore.esp.EspModule
import kz.bejiihiu.servercore.esp.NoopViewStateApplier
import kz.bejiihiu.servercore.esp.SpecObserverSource
import kz.bejiihiu.servercore.spectator.SpecViewStateApplier

/**
 * Anti-esp visibility engine.
 *
 * Guest flags flow in so guest-viewing-guest stays vanilla, and the spec
 * registry flags in so a watching admin renders as nothing to the player they
 * watch. The spec applier wraps the packet bridge (the no-op when pe is absent
 * and no writer exists at all): observer pairs are forced hidden, everything
 * else passes straight through.
 */
class EspInstaller(
    private val ctx: PluginContext,
    private val guest: GuestInstaller,
    private val observer: SpecObserverInstaller,
    private val sync: SyncInstaller,
) {

    private var esp: EspModule? = null

    /** config flag, kept so the self-check can tell "off" from "failed" */
    var enabledByConfig: Boolean = false
        private set

    /** true while the engine object exists and its cycle runs */
    val active: Boolean get() = esp != null

    fun install() {
        val config = EspConfig.load(ctx.plugin.config, ctx.logger)
        enabledByConfig = config.enabled
        if (!config.enabled) return

        val module = EspModule.create(
            ctx.plugin,
            config,
            guests = guest.sessions::isRestricted,
            spec = SpecObserverSource { observer.sessions.isObserver(it) },
        ).also { it.start() }
        esp = module

        module.engine.applier = SpecViewStateApplier(
            observer.sessions,
            observer.hider,
            sync.bridge ?: NoopViewStateApplier,
        )
    }

    fun stop() {
        esp?.stop()
    }
}
