package kz.bejiihiu.servercore.spectator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ViewerLayoutTest {

    private fun marker(c: Char): SlotItem = SlotItem(c.toString(), 1, c.code)

    private val main: List<SlotItem> = ('a'..'d').flatMap { c ->
        (0..8).map { marker((c.code * 9 + it).toChar()) }
    } // 36 entries, letters a..d per row

    @Test
    fun `main storage slots land on display rows 0-2`() {
        val out = ViewerLayout.map(main, null, null, null, null, null, null)
        // storage slot 9 -> display 0, storage 35 -> display 26
        for (storage in 9..35) {
            assertEquals(main[storage], out[ViewerLayout.MAIN_START + storage - 9])
        }
        assertEquals(main[9], out[0])
        assertEquals(main[35], out[26])
    }

    @Test
    fun `hotbar lands on row 3 in original order`() {
        val out = ViewerLayout.map(main, null, null, null, null, null, null)
        for (hotbar in 0..8) {
            assertEquals(main[hotbar], out[ViewerLayout.HOTBAR_START + hotbar])
        }
    }

    @Test
    fun `armor offhand and held mirror sit on row 4`() {
        val helmet = marker('H')
        val chest = marker('C')
        val legs = marker('L')
        val boots = marker('B')
        val offhand = marker('O')
        val held = marker('M')
        val out = ViewerLayout.map(main, helmet, chest, legs, boots, offhand, held)
        assertEquals(helmet, out[36])
        assertEquals(chest, out[37])
        assertEquals(legs, out[38])
        assertEquals(boots, out[39])
        assertEquals(offhand, out[40])
        assertEquals(held, out[41])
    }

    @Test
    fun `filler row stays empty and total size is the backing container`() {
        val out = ViewerLayout.map(main, marker('H'), null, null, null, null, null)
        assertEquals(ViewerLayout.SIZE, out.size)
        for (i in ViewerLayout.SLOTS_SHOWN until ViewerLayout.SIZE) {
            assertNull(out[i])
        }
    }

    @Test
    fun `wrong main inventory size is rejected loudly`() {
        assertThrows(IllegalArgumentException::class.java) {
            ViewerLayout.map(main.drop(1), null, null, null, null, null, null)
        }
    }
}
