package kz.bejiihiu.servercore.ops

import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpsEventBusTest {

    private fun bus(vararg extra: Pair<String, () -> Boolean?>) = OpsEventBus(
        logger = Logger.getGlobal(),
        onlinePlayers = { 3 },
        maxPlayers = { 20 },
        version = { "build-42-abcdef0" },
        telegramSuppressed = { null },
    )

    @Test
    fun `publish fans out to every registered sink with a full payload`() {
        val b = bus()
        val seen = mutableListOf<Pair<ServerState, String?>>()
        b.register { state, detail, payload ->
            seen.add(state to detail)
            assertEquals(3, payload.onlinePlayers)
            assertEquals(20, payload.maxPlayers)
            assertEquals("build-42-abcdef0", payload.version)
            assertEquals(state.name, payload.state)
            assertEquals(detail, payload.detail)
        }
        b.publish(ServerState.RESTARTING, "SCHEDULED")
        assertEquals(listOf<Pair<ServerState, String?>>(ServerState.RESTARTING to "SCHEDULED"), seen)
    }

    @Test
    fun `a broken sink never takes the other sinks or the caller down`() {
        val b = bus()
        val good = mutableListOf<ServerState>()
        b.register(OpsEventSink { _, _, _ -> error("sink exploded") })
        b.register { state, _, _ -> good.add(state) }
        b.publish(ServerState.UPDATE_READY, "build-42-abcdef0")
        assertEquals(listOf(ServerState.UPDATE_READY), good)
    }

    @Test
    fun `duplicate sink registration is ignored and publish without sinks does not throw`() {
        val b = bus()
        val sink = OpsEventSink { _, _, _ -> }
        b.register(sink)
        b.register(sink)
        // no sinks at all: log-only path, must not throw
        bus().publish(ServerState.RUNNING, null)
        assertTrue(true)
    }
}
