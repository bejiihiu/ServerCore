package kz.bejiihiu.servercore.esp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RayMarchTest {

    @Test
    fun samePointGivesNoVoxels() {
        assertEquals(emptyList(), RayMarch.voxels(Vec3(1.5, 64.0, 1.5), Vec3(1.5, 64.0, 1.5)))
    }

    @Test
    fun straightAxisRayVisitsInnerVoxelsOnly() {
        // from (0.5,64,0.5) to (3.5,64,0.5): voxels 1 and 2 in between, the
        // start and end voxels themselves must not appear
        val voxels = RayMarch.voxels(Vec3(0.5, 64.0, 0.5), Vec3(3.5, 64.0, 0.5))
        assertEquals(listOf(VoxelPos(1, 64, 0), VoxelPos(2, 64, 0)), voxels)
    }

    @Test
    fun negativeDirectionWalksDown() {
        // start voxel is floor(-0.5) = -1, end voxel is floor(-3.5) = -4,
        // so the inner voxels are -2 and -3
        val voxels = RayMarch.voxels(Vec3(-0.5, 64.0, 0.5), Vec3(-3.5, 64.0, 0.5))
        assertEquals(listOf(VoxelPos(-2, 64, 0), VoxelPos(-3, 64, 0)), voxels)
    }

    @Test
    fun diagonalRayHasAxisAlignedVoxelCount() {
        // 45 degree diagonal over 4 blocks: 4 steps on x and y, voxel count
        // stays in sane bounds (never more than per-axis steps + 1 per axis)
        val voxels = RayMarch.voxels(Vec3(0.5, 64.5, 0.5), Vec3(4.5, 68.5, 0.5))
        assertTrue(voxels.size in 4..9, "unexpected count ${voxels.size}")
        // every voxel sits on the line
        for (v in voxels) {
            val cx = v.x + 0.5
            val cy = v.y + 0.5
            val onLine = kotlin.math.abs((cx - 0.5) - (cy - 64.5)) < 1.5
            assertTrue(onLine, "voxel off the line: $v")
        }
    }

    @Test
    fun verticalRayStaysInOneColumn() {
        // end voxel (70) is excluded like every end voxel
        val voxels = RayMarch.voxels(Vec3(0.5, 60.5, 0.5), Vec3(0.5, 70.5, 0.5))
        assertEquals((61..69).map { VoxelPos(0, it, 0) }, voxels)
    }

    @Test
    fun chunkFieldsMatchTheVoxel() {
        val voxel = VoxelPos(20, 64, -5)
        assertEquals(1, voxel.chunkX)
        assertEquals(-1, voxel.chunkZ)
    }
}
