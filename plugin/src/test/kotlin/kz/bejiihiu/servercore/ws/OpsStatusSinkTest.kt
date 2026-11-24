package kz.bejiihiu.servercore.ws

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kz.bejiihiu.servercore.ops.ServerState
import kz.bejiihiu.servercore.ops.ServerStatusPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * the ops status frames must satisfy the backend zod schema field for field:
 * required counters always present, optional fields omitted (not null) when
 * absent, state/detail/telegramSuppressed carried verbatim.
 */
class OpsStatusSinkTest {

    private class Captured(
        val id: String,
        val type: String,
        val payload: JsonObject,
        val correlationId: String?,
    )

    private fun sinkWith(captures: MutableList<Captured>): OpsStatusSink =
        OpsStatusSink { id: String, type: String, payload: JsonObject, correlationId: String? ->
            captures.add(Captured(id, type, payload, correlationId))
            true
        }

    @Test
    fun `publishes a full status frame with state and detail`() {
        val captures = mutableListOf<Captured>()
        sinkWith(captures).publish(
            ServerState.RESTARTING,
            "SHA256SUMS ok",
            ServerStatusPayload(onlinePlayers = 3, maxPlayers = 20, version = "1.0.0", state = "RESTARTING", detail = "SHA256SUMS ok"),
        )

        assertEquals(1, captures.size)
        val frame = captures.single()
        assertEquals("SERVER_STATUS", frame.type)
        assertNull(frame.correlationId)
        assertEquals(32, frame.id.length)
        val payload = frame.payload
        assertEquals(JsonPrimitive(3), payload["onlinePlayers"])
        assertEquals(JsonPrimitive(20), payload["maxPlayers"])
        assertEquals(JsonPrimitive("1.0.0"), payload["version"])
        assertEquals(JsonPrimitive("RESTARTING"), payload["state"])
        assertEquals(JsonPrimitive("SHA256SUMS ok"), payload["detail"])
    }

    @Test
    fun `optional fields absent from the payload stay absent on the wire`() {
        val captures = mutableListOf<Captured>()
        sinkWith(captures).publish(
            ServerState.RUNNING,
            null,
            ServerStatusPayload(onlinePlayers = 0, maxPlayers = 5, state = "RUNNING"),
        )

        val payload = captures.single().payload
        assertFalse(payload.containsKey("tps"))
        assertFalse(payload.containsKey("version"))
        assertFalse(payload.containsKey("detail"))
        assertFalse(payload.containsKey("telegramSuppressed"))
        assertTrue(payload.containsKey("onlinePlayers"))
        assertTrue(payload.containsKey("maxPlayers"))
        assertTrue(payload.containsKey("state"))
    }

    @Test
    fun `telegram suppression flag travels when the bridge provides it`() {
        val captures = mutableListOf<Captured>()
        sinkWith(captures).publish(
            ServerState.STOPPING,
            null,
            ServerStatusPayload(onlinePlayers = 1, maxPlayers = 2, state = "STOPPING", telegramSuppressed = true),
        )

        assertEquals(JsonPrimitive(true), captures.single().payload["telegramSuppressed"])
    }
}
