package kz.bejiihiu.servercore.bars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kz.bejiihiu.servercore.ws.NetworkServerSnapshot
import kz.bejiihiu.servercore.ws.NetworkSnapshotPayload

/**
 * the network bar's pure half: totals, per-server staleness and the
 * self-view fallback. the source is a plain map, no websocket and no server.
 */
class NetMetricTest {

    private class FakeSource(var snapshot: NetSnapshot? = null, var clock: Long = 0L) : NetSnapshotSource {
        override fun latest(): NetSnapshot? = snapshot
        override fun now(): Long = clock
    }

    private val source = FakeSource()

    private fun metric(staleAfterMs: Long = 45_000L) = NetMetric(source, staleAfterMs) { LocalNetView(4) }

    private fun server(id: String, players: Int = 1, ageMs: Long = 1_000L) =
        NetServerView(serverId = id, onlinePlayers = players, lastSeenAgeMs = ageMs)

    @Test
    fun `a healthy network renders the server fraction and the player total`() {
        source.snapshot = NetSnapshot(
            playersOnline = 7,
            servers = listOf(server("game-1", 5), server("game-2", 2), server("game-3", 0)),
            receivedAt = source.clock,
        )

        val reading = metric().readGlobal()

        assertEquals("3/3", reading.values["servers"])
        assertEquals("7", reading.values["players"])
        assertEquals("0", reading.values["stale"])
        assertEquals(1.0, reading.fraction)
        assertEquals(BarHealth.GOOD, reading.health)
        assertNull(reading.titleKey, "a fully healthy network keeps the plain title")
    }

    @Test
    fun `a server quieter than the window counts as offline, separately`() {
        source.snapshot = NetSnapshot(
            playersOnline = 7,
            servers = listOf(server("game-1", 5), server("game-2", 2, ageMs = 90_000L)),
            receivedAt = source.clock,
        )

        val reading = metric().readGlobal()

        assertEquals("1/2", reading.values["servers"])
        assertEquals("1", reading.values["stale"])
        assertEquals(0.5, reading.fraction)
        assertEquals(BarHealth.MEDIUM, reading.health)
        assertEquals("bars.net.title-stale", reading.titleKey)
    }

    @Test
    fun `nothing answering is low, not unknown`() {
        source.snapshot = NetSnapshot(
            playersOnline = 6,
            servers = listOf(server("game-1", 3, 120_000L), server("game-2", 3, 300_000L)),
            receivedAt = source.clock,
        )

        val reading = metric().readGlobal()

        assertEquals("0/2", reading.values["servers"])
        assertEquals("2", reading.values["stale"])
        assertEquals(0.0, reading.fraction)
        assertEquals(BarHealth.LOW, reading.health)
    }

    @Test
    fun `a picture keeps aging between pushes`() {
        // every server was fresh when the frame arrived...
        source.snapshot = NetSnapshot(playersOnline = 3, servers = listOf(server("game-1", 3)), receivedAt = 0L)
        source.clock = 10_000L
        assertEquals("1/1", metric().readGlobal().values["servers"])

        // ...and the backend has been silent for longer than the window since
        source.clock = 60_000L
        val aged = metric().readGlobal()
        assertEquals("0/1", aged.values["servers"])
        assertEquals("1", aged.values["stale"])
    }

    @Test
    fun `the edge of the window is still online`() {
        source.snapshot = NetSnapshot(playersOnline = 1, servers = listOf(server("game-1", 1, 1_000L)), receivedAt = 0L)
        source.clock = 44_000L // 1s at generation + 44s since = exactly 45s
        assertEquals("1/1", metric(staleAfterMs = 45_000L).readGlobal().values["servers"])

        source.clock = 44_001L
        assertEquals("0/1", metric(staleAfterMs = 45_000L).readGlobal().values["servers"])
    }

    @Test
    fun `without an aggregate this server is a network of one`() {
        source.snapshot = null
        val reading = metric().readGlobal()

        assertEquals("1/1", reading.values["servers"])
        assertEquals("4", reading.values["players"], "the local player count is the honest answer")
        assertEquals("0", reading.values["stale"])
        assertEquals(BarHealth.GOOD, reading.health)
        assertEquals(1.0, reading.fraction)
    }

    @Test
    fun `an empty aggregate falls back to the same self view`() {
        source.snapshot = NetSnapshot(playersOnline = 0, servers = emptyList(), receivedAt = 0L)

        assertEquals("1/1", metric().readGlobal().values["servers"])
    }

    @Test
    fun `the payload is normalised on the way in`() {
        val cache = NetworkStateCache { 0L }
        cache.accept(
            NetworkSnapshotPayload(
                generatedAt = 0L,
                playersOnline = -5,
                servers = listOf(
                    NetworkServerSnapshot(
                        serverId = "game-1",
                        onlinePlayers = -1,
                        maxPlayers = 0,
                        lastSeenAgeMs = -100L,
                    ),
                ),
            ),
        )

        val snapshot = cache.latest()!!
        assertEquals(0, snapshot.playersOnline)
        assertEquals(0L, snapshot.servers.single().lastSeenAgeMs)
        assertEquals(0, snapshot.servers.single().onlinePlayers)
    }

    @Test
    fun `clearing the cache puts the bar back on the self view`() {
        val cache = NetworkStateCache { 0L }
        cache.accept(
            NetworkSnapshotPayload(
                generatedAt = 0L,
                playersOnline = 12,
                servers = listOf(
                    NetworkServerSnapshot(serverId = "game-1", onlinePlayers = 12, maxPlayers = 60, lastSeenAgeMs = 0L),
                ),
            ),
        )
        assertEquals(12, cache.latest()!!.playersOnline)

        cache.clear()
        assertNull(cache.latest())
    }
}
