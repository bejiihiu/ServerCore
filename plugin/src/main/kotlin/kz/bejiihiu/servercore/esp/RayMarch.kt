package kz.bejiihiu.servercore.esp

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** one voxel coordinate on the ray path */
data class VoxelPos(val x: Int, val y: Int, val z: Int) {
    val chunkX: Int get() = x shr 4
    val chunkZ: Int get() = z shr 4
}

/**
 * pure voxel traversal (amanatides & woo dda), no bukkit and no world access.
 * the traversal list is built off-thread; only the *reads* of the listed
 * blocks are pushed onto the regions that own them.
 *
 * the start and end voxels are intentionally not reported: the viewer's eye
 * block is air by definition and the target's head block must not occlude the
 * target itself.
 */
object RayMarch {

    // worst case with the config-capped 128 block distance is ~390 voxels on a
    // full diagonal, 1024 is pure paranoia against float rounding loops
    private const val MAX_VOXELS = 1024

    fun voxels(start: Vec3, end: Vec3): List<VoxelPos> {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val dz = end.z - start.z
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 1e-9) return emptyList()
        val ux = dx / length
        val uy = dy / length
        val uz = dz / length

        var bx = floor(start.x).toInt()
        var by = floor(start.y).toInt()
        var bz = floor(start.z).toInt()
        val endX = floor(end.x).toInt()
        val endY = floor(end.y).toInt()
        val endZ = floor(end.z).toInt()

        val stepX = if (ux > 0) 1 else -1
        val stepY = if (uy > 0) 1 else -1
        val stepZ = if (uz > 0) 1 else -1

        // distance along the ray between consecutive voxel boundaries per axis
        val tDeltaX = if (ux == 0.0) Double.POSITIVE_INFINITY else abs(1.0 / ux)
        val tDeltaY = if (uy == 0.0) Double.POSITIVE_INFINITY else abs(1.0 / uy)
        val tDeltaZ = if (uz == 0.0) Double.POSITIVE_INFINITY else abs(1.0 / uz)

        var tMaxX = if (ux == 0.0) Double.POSITIVE_INFINITY
        else (if (ux > 0) bx + 1.0 - start.x else start.x - bx) * tDeltaX
        var tMaxY = if (uy == 0.0) Double.POSITIVE_INFINITY
        else (if (uy > 0) by + 1.0 - start.y else start.y - by) * tDeltaY
        var tMaxZ = if (uz == 0.0) Double.POSITIVE_INFINITY
        else (if (uz > 0) bz + 1.0 - start.z else start.z - bz) * tDeltaZ

        val out = ArrayList<VoxelPos>(estimateVoxels(bx, by, bz, endX, endY, endZ))
        while (bx != endX || by != endY || bz != endZ) {
            when {
                tMaxX <= tMaxY && tMaxX <= tMaxZ -> {
                    bx += stepX
                    tMaxX += tDeltaX
                }
                tMaxY <= tMaxZ -> {
                    by += stepY
                    tMaxY += tDeltaY
                }
                else -> {
                    bz += stepZ
                    tMaxZ += tDeltaZ
                }
            }
            out += VoxelPos(bx, by, bz)
            if (out.size >= MAX_VOXELS) break
        }
        // the end voxel is the target's own head block, it must never count as
        // cover against the target itself (a head under leaves would otherwise
        // disguise someone standing two blocks away)
        if (out.isNotEmpty()) {
            val last = out[out.size - 1]
            if (last.x == endX && last.y == endY && last.z == endZ) out.removeAt(out.size - 1)
        }
        return out
    }

    /**
     * the traversal visits at most manhattan(voxel delta) + 1 voxels, so one
     * right-sized list never has to grow. capped at the hard limit so a bogus
     * far-away endpoint can't ask for a huge array.
     */
    private fun estimateVoxels(bx: Int, by: Int, bz: Int, endX: Int, endY: Int, endZ: Int): Int {
        val manhattan = abs(endX - bx) + abs(endY - by) + abs(endZ - bz)
        return minOf(manhattan, MAX_VOXELS - 1) + 1
    }
}
