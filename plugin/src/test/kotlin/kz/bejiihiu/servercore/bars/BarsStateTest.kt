package kz.bejiihiu.servercore.bars

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * the toggle rules: one bar per surface, the surface is part of the key, and
 * the group toggle is deterministic instead of "flip whatever each has".
 */
class BarsStateTest {

    private val alice: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val bob: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun `toggle turns a bar on and off again`() {
        val state = BarsState()

        assertTrue(state.toggle(alice, BarId.RAM, BarSurface.BOSSBAR))
        assertEquals(BarId.RAM, state.active(alice, BarSurface.BOSSBAR))

        assertFalse(state.toggle(alice, BarId.RAM, BarSurface.BOSSBAR))
        assertNull(state.active(alice, BarSurface.BOSSBAR))
    }

    @Test
    fun `enabling another bar on the same surface displaces the first`() {
        val state = BarsState()
        state.enable(alice, BarId.RAM, BarSurface.BOSSBAR)

        assertTrue(state.enable(alice, BarId.TPS, BarSurface.BOSSBAR))
        assertEquals(BarId.TPS, state.active(alice, BarSurface.BOSSBAR))
    }

    @Test
    fun `surfaces are independent`() {
        val state = BarsState()

        state.enable(alice, BarId.RAM, BarSurface.BOSSBAR)
        state.enable(alice, BarId.TPS, BarSurface.ACTIONBAR)

        assertEquals(BarId.RAM, state.active(alice, BarSurface.BOSSBAR))
        assertEquals(BarId.TPS, state.active(alice, BarSurface.ACTIONBAR))
    }

    @Test
    fun `disable only clears the bar that is actually on that surface`() {
        val state = BarsState()
        state.enable(alice, BarId.RAM, BarSurface.BOSSBAR)

        assertFalse(state.disable(alice, BarId.TPS, BarSurface.BOSSBAR))
        assertEquals(BarId.RAM, state.active(alice, BarSurface.BOSSBAR))

        assertTrue(state.disable(alice, BarId.RAM, BarSurface.BOSSBAR))
        assertNull(state.active(alice, BarSurface.BOSSBAR))
    }

    @Test
    fun `re-enabling the active bar reports no change`() {
        val state = BarsState()
        state.enable(alice, BarId.RAM, BarSurface.BOSSBAR)

        assertFalse(state.enable(alice, BarId.RAM, BarSurface.BOSSBAR))
    }

    @Test
    fun `the group toggle turns everyone on, then everyone off`() {
        val state = BarsState()
        state.enable(alice, BarId.RAM, BarSurface.BOSSBAR)

        // mixed group: the intent is "on"
        assertTrue(state.toggleAll(listOf(alice, bob), BarId.RAM, BarSurface.BOSSBAR))
        assertEquals(BarId.RAM, state.active(alice, BarSurface.BOSSBAR))
        assertEquals(BarId.RAM, state.active(bob, BarSurface.BOSSBAR))

        // every player already has it: the group goes off
        assertFalse(state.toggleAll(listOf(alice, bob), BarId.RAM, BarSurface.BOSSBAR))
        assertNull(state.active(alice, BarSurface.BOSSBAR))
        assertNull(state.active(bob, BarSurface.BOSSBAR))
    }

    @Test
    fun `an empty group toggle changes nothing`() {
        assertFalse(BarsState().toggleAll(emptyList(), BarId.RAM, BarSurface.BOSSBAR))
    }

    @Test
    fun `snapshots are json friendly and reload into the same state`() {
        val state = BarsState()
        state.enable(alice, BarId.RAM, BarSurface.BOSSBAR)
        state.enable(bob, BarId.TPS, BarSurface.ACTIONBAR)

        assertEquals(
            mapOf(
                alice.toString() to mapOf("bossbar" to "ram"),
                bob.toString() to mapOf("actionbar" to "tps"),
            ),
            state.snapshot(),
        )

        val reloaded = BarsState(
            state.snapshot().mapKeys { (uuid, _) -> UUID.fromString(uuid) }
                .mapValues { (_, bySurface) ->
                    bySurface.mapKeys { (surface, _) -> BarSurface.parse(surface)!! }
                        .mapValues { (_, bar) -> BarId.entries.first { it.key == bar } }
                },
        )
        assertEquals(BarId.RAM, reloaded.active(alice, BarSurface.BOSSBAR))
        assertEquals(BarId.TPS, reloaded.active(bob, BarSurface.ACTIONBAR))
    }

    @Test
    fun `clear drops every surface of one player`() {
        val state = BarsState()
        state.enable(alice, BarId.RAM, BarSurface.BOSSBAR)
        state.enable(alice, BarId.TPS, BarSurface.ACTIONBAR)

        state.clear(alice)

        assertNull(state.active(alice, BarSurface.BOSSBAR))
        assertNull(state.active(alice, BarSurface.ACTIONBAR))
    }
}
