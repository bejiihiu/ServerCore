package kz.bejiihiu.servercore.esp

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpatialGridTest {

    private val grid = SpatialGrid()
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val c = UUID.randomUUID()

    @Test
    fun nearbyFindsPlayersInTheSameAndAdjacentCells() {
        grid.update(a, SpatialGrid.Cell("world", 0, 0))
        grid.update(b, SpatialGrid.Cell("world", 1, 0))
        grid.update(c, SpatialGrid.Cell("world", 5, 5))

        val found = grid.nearby(SpatialGrid.Cell("world", 0, 0), 1, a)
        assertTrue(a !in found)
        assertTrue(b in found)
        assertTrue(c !in found)
    }

    @Test
    fun worldsAreIsolated() {
        grid.update(a, SpatialGrid.Cell("world", 0, 0))
        grid.update(b, SpatialGrid.Cell("nether", 0, 0))
        val found = grid.nearby(SpatialGrid.Cell("world", 0, 0), 1, a)
        assertTrue(b !in found)
        assertTrue(found.isEmpty())
    }

    @Test
    fun movingBetweenCellsLeavesNoGhostEntries() {
        grid.update(a, SpatialGrid.Cell("world", 0, 0))
        grid.update(a, SpatialGrid.Cell("world", 2, 2))

        assertNull(grid.nearby(SpatialGrid.Cell("world", 0, 0), 0, a).firstOrNull { it == a })
        assertEquals(a, grid.nearby(SpatialGrid.Cell("world", 2, 2), 0, b).single())
        assertEquals(1, grid.tracked())
    }

    @Test
    fun sameCellUpdateIsACheapNoOp() {
        val cell = SpatialGrid.Cell("world", 3, 3)
        grid.update(a, cell)
        grid.update(a, cell)
        grid.update(a, cell)
        assertEquals(listOf(a), grid.nearby(cell, 0, b))
    }

    @Test
    fun removeCleansBucketAndLocation() {
        grid.update(a, SpatialGrid.Cell("world", 0, 0))
        grid.remove(a)
        assertEquals(0, grid.tracked())
        assertNull(grid.cellOf(a))
        assertTrue(grid.nearby(SpatialGrid.Cell("world", 0, 0), 1, b).isEmpty())
    }

    @Test
    fun clearWipesEverything() {
        grid.update(a, SpatialGrid.Cell("world", 0, 0))
        grid.update(b, SpatialGrid.Cell("world", 1, 1))
        grid.clear()
        assertEquals(0, grid.tracked())
    }
}
