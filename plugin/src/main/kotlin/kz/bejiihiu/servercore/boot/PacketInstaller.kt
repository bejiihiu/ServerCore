package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.disguise.DisguiseStack
import kz.bejiihiu.servercore.packet.CachedViewStateStore
import kz.bejiihiu.servercore.packet.PacketConfig
import kz.bejiihiu.servercore.packet.PacketService
import kz.bejiihiu.servercore.packet.PacketThreads
import kz.bejiihiu.servercore.packet.WsSyncedViewStateStore

/**
 * The packetevents layer: the personal entity view foundation plus the
 * client-only disguise entities the anti-esp visibility engine emits.
 *
 * The disguise stack only comes up when the packet pipeline is actually
 * alive; without pe the engine's disguise verdicts degrade to plain hiding.
 * Service and disguise tear down in different slots on purpose — the session
 * machinery restores views through the packet bridge before our listener is
 * detached, while the disguise layer outlives the esp engine that drives it.
 */
class PacketInstaller(private val ctx: PluginContext) {

    lateinit var service: PacketService
        private set

    var disguise: DisguiseStack? = null
        private set

    /** config flag, kept so the self-check can tell "off" from "failed" */
    var enabledByConfig: Boolean = false
        private set

    /** true while our listener is attached to the shared packetevents instance */
    val online: Boolean get() = if (this::service.isInitialized) service.online else false

    fun install() {
        val plugin = ctx.plugin
        val packetConfig = PacketConfig.load(plugin.config)
        enabledByConfig = packetConfig.enabled
        val packets = PacketService(
            plugin = plugin,
            config = packetConfig,
            store = WsSyncedViewStateStore(
                CachedViewStateStore(
                    maxViewers = 1024,
                    maxTargetsPerViewer = 512,
                ),
            ),
            threads = PacketThreads(plugin),
        )
        packets.start()
        service = packets

        disguise = if (packets.online) DisguiseStack.create(plugin, plugin.config, ctx.logger) else null
        if (!packets.online) {
            ctx.logger.warning("packet layer idle, disguise layer staying off")
        }
    }

    /** Detaches our listener, the shared packetevents instance must outlive us. */
    fun stopService() {
        if (this::service.isInitialized) service.stop()
    }

    fun stopDisguise() {
        disguise?.shutdown()
    }
}
