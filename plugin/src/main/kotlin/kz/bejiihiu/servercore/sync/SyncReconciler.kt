package kz.bejiihiu.servercore.sync

import org.bukkit.plugin.Plugin
import java.util.concurrent.TimeUnit

/**
 * folia adapter for the periodic reconciliation pass. drift repair only reads
 * the sync module's own concurrent state (registry rows + identity bindings),
 * never bukkit entities, so the async scheduler is the correct home for it
 * per the folia threading rules. any packet work the executor decides on is
 * the writer's own threading responsibility.
 */
class SyncReconciler(
    private val plugin: Plugin,
    private val sync: PacketStateSync,
    private val periodSeconds: Long = 15L,
) {

    private var handle: io.papermc.paper.threadedregions.scheduler.ScheduledTask? = null

    fun start() {
        handle = plugin.server.asyncScheduler.runAtFixedRate(
            plugin,
            { _ -> sync.reconcile() },
            periodSeconds,
            periodSeconds,
            TimeUnit.SECONDS,
        )
    }

    fun stop() {
        handle?.cancel()
        handle = null
    }
}
