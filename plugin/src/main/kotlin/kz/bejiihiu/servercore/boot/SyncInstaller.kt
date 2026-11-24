package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.sync.EntityIdentityTracker
import kz.bejiihiu.servercore.sync.InMemoryViewStateRegistry
import kz.bejiihiu.servercore.sync.PacketResyncExecutor
import kz.bejiihiu.servercore.sync.PacketStateListener
import kz.bejiihiu.servercore.sync.PacketStateSync
import kz.bejiihiu.servercore.sync.SyncReconciler
import kz.bejiihiu.servercore.sync.VanillaOnlyResyncExecutor

/**
 * Packet state sync: every client-view state transition goes through the
 * double-load-guarded coordinator.
 *
 * With pe up, resyncs run through the packet bridge — it keeps the policy rows,
 * mirrors the applied picture into the packet registry and drives the disguise
 * service, so an invalidation emits real packets. Without pe the vanilla-only
 * fallback keeps registry state correct without emitting packets.
 *
 * The registry and the bridge are read by the observer hider and the esp
 * applier, so they stay exposed.
 */
class SyncInstaller(
    private val ctx: PluginContext,
    private val packet: PacketInstaller,
) {

    val states = InMemoryViewStateRegistry()

    var bridge: PacketResyncExecutor? = null
        private set

    private var packetSync: PacketStateSync? = null
    private var reconciler: SyncReconciler? = null

    fun install() {
        val packets = packet.service
        val entityIds = EntityIdentityTracker()
        bridge = if (packets.online) {
            PacketResyncExecutor(packets.registry, states, packet.disguise?.applier, ctx.logger)
        } else {
            null
        }

        val sync = PacketStateSync.create(
            states = states,
            resync = bridge ?: VanillaOnlyResyncExecutor(ctx.logger),
            identities = entityIds,
            logger = ctx.logger,
        )
        packetSync = sync
        if (sync == null) {
            ctx.logger.severe("packet state sync unavailable (double load), listeners not registered")
        } else {
            ctx.plugin.server.pluginManager.registerEvents(PacketStateListener(sync, entityIds), ctx.plugin)
            reconciler = SyncReconciler(ctx.plugin, sync).also { it.start() }
        }
    }

    fun stop() {
        reconciler?.stop()
        packetSync?.let { sync ->
            sync.restoreAllForReload()
            sync.release()
        }
    }
}
