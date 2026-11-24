package kz.bejiihiu.servercore.esp

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.bukkit.World
import org.bukkit.plugin.Plugin

/** one probe ray in world coordinates */
data class Ray(val start: Vec3, val end: Vec3)

/**
 * folia-correct line-of-sight. bukkit's world.rayTraceBlocks would have to run
 * on the region owning *every block the ray passes through*, which is exactly
 * the thing you cannot guarantee across region borders - so we do it ourselves:
 *
 * 1. the pure dda traversal lists the voxels the ray visits (no world reads)
 * 2. voxels are grouped by chunk - all blocks of a chunk provably belong to
 *    one region, so chunk-level grouping is thread-correct by construction
 * 3. each chunk group is scheduled on that region via the region scheduler,
 *    where the block opacity reads happen; never from any other thread
 * 4. the future completes when every group has reported, no region hopping
 *    mid-trace and no cross-region block access anywhere
 *
 * the whole thing is asynchronous and budgeted by the engine, latency of a
 * tick or two is invisible behind the pair TTL.
 */
class RegionSafeRaytracer(private val plugin: Plugin) {

    /**
     * true when geometry blocks the ray (or when anything went sideways -
     * fail closed), false when at least one unobstructed path exists.
     */
    fun traceBlocked(world: World, ray: Ray, minY: Int, maxY: Int): CompletableFuture<Boolean> {
        val voxels = RayMarch.voxels(ray.start, ray.end)
            .filter { it.y in minY..maxY } // above build limit / below the void is never occluding
        if (voxels.isEmpty()) return CompletableFuture.completedFuture(false)

        val groups = voxels.groupBy { SpatialGrid.Cell(world.name, it.chunkX, it.chunkZ) }
        val blocked = AtomicBoolean(false)
        val remaining = AtomicInteger(groups.size)
        val done = CompletableFuture<Boolean>()

        for ((cell, group) in groups) {
            plugin.server.regionScheduler.execute(plugin, world, cell.cx, cell.cz) {
                try {
                    for (voxel in group) {
                        if (world.getBlockAt(voxel.x, voxel.y, voxel.z).type.isOccluding) {
                            blocked.set(true)
                            break // this chunk already blocks, its other voxels don't matter
                        }
                    }
                } finally {
                    if (remaining.decrementAndGet() == 0) done.complete(blocked.get())
                }
            }
        }
        return done
    }
}
