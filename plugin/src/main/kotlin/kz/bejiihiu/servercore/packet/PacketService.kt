package kz.bejiihiu.servercore.packet

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerCommon
import java.util.logging.Logger
import org.bukkit.plugin.java.JavaPlugin

/**
 * facade of the packet layer, the single entry point other modules use.
 * owns the lifecycle: [start] attaches the entity packet listener to the
 * shared packetevents instance, [stop] detaches it. never touches bukkit
 * state directly — player/world access goes through [threads].
 *
 * lifecycle ownership, deliberate: this plugin depends on the server-installed
 * packetevents plugin (compile-only artifact + required paper-plugin
 * dependency), so we do NOT create our own packetevents instance. building one
 * here would collide with the shared instance other plugins already use.
 * consequences:
 * - no PacketEvents.setAPI/load/init on our side; the shared instance is
 *   already loaded and initialized before we enable (load: BEFORE dependency)
 * - no terminate() on disable either — terminating the shared instance would
 *   tear the pipeline out from under every other consumer
 * - folia needs no special settings: packetevents 2.14 detects folia itself
 *   (FoliaScheduler) when the shared instance boots
 */
class PacketService(
    private val plugin: JavaPlugin,
    val config: PacketConfig,
    store: ViewStateStore,
    val threads: PacketThreads,
    private val logger: Logger = plugin.logger,
) {

    private val compatibility = PacketCompatibilityService()
    val registry = PacketStateRegistry(store)

    /** true while our listener is attached to the shared packetevents instance */
    var online: Boolean = false
        private set

    /** swap point for behaviour modules: visibility engine, disguise, sync */
    @Volatile
    var applier: ViewStateApplier = DefaultViewStateApplier(registry)

    private var listener: PacketListenerCommon? = null

    /** onEnable phase: attach the listener unless disabled or unavailable */
    fun start() {
        if (!config.enabled) {
            logger.info("packet layer disabled by config")
            return
        }
        // refresh: at onLoad the plugin is loaded but not yet enabled
        compatibility.refresh()
        if (!compatibility.isAvailable()) {
            // graceful no-op, never a crash: everything keeps working without packets
            logger.warning("packetevents unavailable, packet layer idle (${compatibility.describe()})")
            return
        }

        val listener = EntityPacketListener(registry) { applier }
        // registerListener's two-arg overload wants the functional interface;
        // we keep our own priority, already passed in the constructor
        PacketEvents.getAPI().getEventManager().registerListener(listener as PacketListenerCommon)
        this.listener = listener
        online = true
        logger.info("packet layer online: ${compatibility.describe()}, store pairs=${registry.size()}")
    }

    /** onDisable phase: detach only our listener, leave the shared instance alone */
    fun stop() {
        val listener = listener ?: return
        if (!compatibility.isAvailable()) {
            this.listener = null
            online = false
            return
        }
        runCatching { PacketEvents.getAPI().getEventManager().unregisterListener(listener) }
            .onFailure { logger.warning("packet listener detach failed: ${it.message}") }
        this.listener = null
        online = false
        registry.clear()
    }

    /**
     * attach an extra scoped listener to the shared instance. false means the
     * packet layer is idle and the caller must not expect the listener to
     * ever fire.
     */
    fun register(listener: PacketListenerCommon): Boolean {
        if (!online) return false
        PacketEvents.getAPI().getEventManager().registerListener(listener)
        return true
    }

    fun unregister(listener: PacketListenerCommon) {
        if (!online) return
        runCatching { PacketEvents.getAPI().getEventManager().unregisterListener(listener) }
            .onFailure { logger.warning("scoped listener detach failed: ${it.message}") }
    }
}
