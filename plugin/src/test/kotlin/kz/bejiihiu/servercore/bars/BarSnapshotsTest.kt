package kz.bejiihiu.servercore.bars

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * the snapshot layer placeholderapi reads. the rule under test: a global
 * reading answers for everyone, a player-scoped one answers only for that
 * player, and an unknown reading is a null, never the em dash.
 */
class BarSnapshotsTest {

    private val steve = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val alex = UUID.fromString("22222222-2222-4222-8222-222222222222")

    private fun reading(key: String, value: String) = BarReading(
        fraction = 0.5,
        health = BarHealth.GOOD,
        values = mapOf(key to value),
    )

    @Test
    fun `nothing published yet answers null`() {
        val snapshots = BarSnapshots()
        assertNull(snapshots.value(BarId.TPS, null))
        assertNull(snapshots.value(BarId.RAM, steve))
    }

    @Test
    fun `a global reading answers for every player`() {
        val snapshots = BarSnapshots()
        snapshots.publish(BarId.TPS, reading("tps", "19.9"))

        assertEquals("19.9", snapshots.value(BarId.TPS, null))
        assertEquals("19.9", snapshots.value(BarId.TPS, steve))
    }

    @Test
    fun `a player reading never leaks into the global slot`() {
        val snapshots = BarSnapshots()
        // region mspt only ever comes from one player's own task
        snapshots.publish(BarId.MSPT, reading("mspt", "12.5"), steve)

        assertNull(snapshots.value(BarId.MSPT, null), "there is no server-wide mspt on folia")
        assertEquals("12.5", snapshots.value(BarId.MSPT, steve))
        assertNull(snapshots.value(BarId.MSPT, alex))
    }

    @Test
    fun `the player's own reading wins over the global one`() {
        val snapshots = BarSnapshots()
        snapshots.publish(BarId.TPS, reading("tps", "20.0"))
        snapshots.publish(BarId.TPS, reading("tps", "14.2"), steve)

        assertEquals("14.2", snapshots.value(BarId.TPS, steve))
        assertEquals("20.0", snapshots.value(BarId.TPS, alex))
        assertEquals("20.0", snapshots.value(BarId.TPS, null))
    }

    @Test
    fun `an unknown reading is not a value`() {
        val snapshots = BarSnapshots()
        snapshots.publish(BarId.RAM, reading("pct", BarFormats.UNKNOWN))

        assertNull(snapshots.value(BarId.RAM, null))
    }

    @Test
    fun `a quitting player takes their readings with them`() {
        val snapshots = BarSnapshots()
        snapshots.publish(BarId.MSPT, reading("mspt", "30.0"), steve)
        snapshots.publish(BarId.MSPT, reading("mspt", "20.0"), alex)

        snapshots.forget(steve)

        assertNull(snapshots.value(BarId.MSPT, steve))
        assertEquals("20.0", snapshots.value(BarId.MSPT, alex))
    }

    @Test
    fun `each bar exports the value a scoreboard wants`() {
        val snapshots = BarSnapshots()
        snapshots.publish(
            BarId.NET,
            BarReading(fraction = 1.0, health = BarHealth.GOOD, values = mapOf("servers" to "3/3", "players" to "7")),
        )

        assertEquals("3/3", snapshots.value(BarId.NET, null))
    }
}
