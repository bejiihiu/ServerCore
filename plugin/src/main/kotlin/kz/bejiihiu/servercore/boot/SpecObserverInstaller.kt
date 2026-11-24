package kz.bejiihiu.servercore.boot

import com.github.retrooper.packetevents.event.PacketListenerCommon
import kz.bejiihiu.servercore.spectator.ObserverHider
import kz.bejiihiu.servercore.spectator.SpecObserverConfig
import kz.bejiihiu.servercore.spectator.SpecResyncListener
import kz.bejiihiu.servercore.spectator.SpecTraceListener
import kz.bejiihiu.servercore.spectator.SpecSessionRegistry as SpectatorSpecSessionRegistry
import kz.bejiihiu.servercore.spectator.TraceFilter

/**
 * Hidden observer layer for /spec: while an admin watches a player, the admin's
 * entity vanishes from that client (destroy + suppressed spawns/data + tab
 * removal) and comes back intact on session end.
 *
 * The session coordinator drives it through the hiding bridge; this registry
 * stays the seam the esp engine and the resync paths read.
 */
class SpecObserverInstaller(
    private val ctx: PluginContext,
    private val packet: PacketInstaller,
    private val sync: SyncInstaller,
) {

    lateinit var sessions: SpectatorSpecSessionRegistry
        private set

    lateinit var hider: ObserverHider
        private set

    private var traceListener: PacketListenerCommon? = null

    fun install() {
        val plugin = ctx.plugin
        val packets = packet.service
        val observerConfig = SpecObserverConfig.load(plugin.config, ctx.logger)

        val observerSessions = SpectatorSpecSessionRegistry()
        sessions = observerSessions
        val traceFilter = TraceFilter(observerConfig.traceRadius, observerConfig.suppressActionBar)
        val hider = ObserverHider(plugin, packets, observerSessions, traceFilter, syncStates = sync.states)
        this.hider = hider

        val trace = SpecTraceListener(observerSessions, packets.registry, hider::poseOf, traceFilter)
        if (packets.register(trace)) {
            traceListener = trace
        } else {
            ctx.logger.warning("packet layer idle, hidden observer suppression inactive")
        }
        plugin.server.pluginManager.registerEvents(SpecResyncListener(hider, observerSessions), plugin)
    }

    fun stop() {
        // hidden observer pairs restore in deterministic order, as a
        // belt-and-braces pass over anything the session sweep did not cover
        if (this::hider.isInitialized) hider.shutdown()
        traceListener?.let { listener -> packet.service.unregister(listener) }
    }
}
