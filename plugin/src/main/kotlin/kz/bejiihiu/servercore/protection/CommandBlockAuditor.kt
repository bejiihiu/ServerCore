package kz.bejiihiu.servercore.protection

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.function.Consumer
import java.util.logging.Logger
import org.bukkit.block.CommandBlock
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * command blocks never fire a bukkit event when they execute, so their
 * commands are audited at rest instead: on chunk load (on the region thread
 * that owns the chunk) plus a slow periodic pass over loaded chunks. a block
 * whose stored command matches the denied set is logged, and cleared in
 * place when neutralize is on.
 *
 * best-effort by nature: blocks edited while loaded are only caught by the
 * periodic pass, command block minecarts are not scanned, and a function
 * invoked by a block is invisible from here. the honest guarantee for
 * players stays with the preprocess listener.
 */
class CommandBlockAuditor(
    private val plugin: JavaPlugin,
    private val config: ProtectionConfig,
    private val service: CommandProtectionService,
    private val logger: Logger,
) : Listener {

    private var auditTask: ScheduledTask? = null

    fun start() {
        val period = config.commandBlockAuditIntervalSeconds * 20
        if (period <= 0) {
            logger.info("command block audit disabled (interval 0)")
            return
        }
        auditTask = plugin.server.globalRegionScheduler.runAtFixedRate(
            plugin,
            Consumer { _ -> auditAll() },
            period,
            period,
        )
    }

    fun stop() {
        auditTask?.cancel()
        auditTask = null
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (!config.enabled) return
        auditChunk(event.chunk)
    }

    /** global scheduler entry point; per-chunk work hops to the owning region */
    fun auditAll() {
        if (!config.enabled) return
        for (world in plugin.server.worlds) {
            // snapshotting coordinates off-thread is a benign race: worst case
            // the pass misses a freshly loaded chunk or schedules one that just
            // unloaded, both corrected on the next pass
            val coords = world.loadedChunks.map { it.x to it.z }
            for ((x, z) in coords) {
                plugin.server.regionScheduler.run(
                    plugin,
                    world,
                    x,
                    z,
                    Consumer { _ -> auditLoadedChunk(world.name, x, z) },
                )
            }
        }
    }

    private fun auditLoadedChunk(worldName: String, x: Int, z: Int) {
        val world = plugin.server.getWorld(worldName) ?: return
        if (!world.isChunkLoaded(x, z)) return
        auditChunk(world.getChunkAt(x, z))
    }

    private fun auditChunk(chunk: org.bukkit.Chunk) {
        for (state in chunk.tileEntities) {
            val block = state as? CommandBlock ?: continue
            auditBlock(block)
        }
    }

    private fun auditBlock(block: CommandBlock) {
        val command = block.command
        if (command.isBlank()) return
        val match = service.match(command) ?: return
        val location = block.location
        val where = "${location.world?.name} ${location.blockX} ${location.blockY} ${location.blockZ}"
        if (config.commandBlockNeutralize) {
            block.setCommand("")
            block.update()
            logger.warning("cleared command block at $where - it carried a denied command ($match)")
        } else {
            logger.warning(
                "command block at $where carries a denied command ($match) - " +
                    "set protection.command-block-neutralize to true to clear such blocks automatically",
            )
        }
    }
}
