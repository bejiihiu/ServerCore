package kz.bejiihiu.servercore.websocket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * the ops bus publishes SERVER_STATUS frames through the gateway; the backend
 * schema carries state/detail/telegramSuppressed on top of the counts. these
 * tests pin the round trip so the ops seam cannot silently drop the fields
 * again.
 */
class ServerStatusPayloadTest {

    private val codec = EnvelopeCodec("0123456789abcdef0123456789abcdef")
    private val now = 1_700_000_000_000L

    private fun encode(payload: Payload.ServerStatus): String =
        codec.encode(Envelope(codec.newMessageId(), MessageType.SERVER_STATUS, now, payload))

    @Test
    fun `ops fields travel when present`() {
        val wire = encode(
            Payload.ServerStatus(
                onlinePlayers = 3,
                maxPlayers = 20,
                version = "1.0.0",
                state = "RESTARTING",
                detail = "daily restart",
                telegramSuppressed = true,
            ),
        )
        val decoded = codec.decode(wire, now, 30_000, 262_144)
        val payload = decoded.envelope.payload as Payload.ServerStatus
        assertEquals(3, payload.onlinePlayers)
        assertEquals("RESTARTING", payload.state)
        assertEquals("daily restart", payload.detail)
        assertEquals(true, payload.telegramSuppressed)
    }

    @Test
    fun `ops fields stay absent when unset so the hello frame shape is unchanged`() {
        val wire = encode(Payload.ServerStatus(onlinePlayers = 0, maxPlayers = 10, version = "1.0.0"))
        val decoded = codec.decode(wire, now, 30_000, 262_144)
        val payload = decoded.envelope.payload as Payload.ServerStatus
        assertNull(payload.state)
        assertNull(payload.detail)
        assertNull(payload.telegramSuppressed)

        val raw = decoded.rawPayload as JsonObject
        assertNull(raw["state"])
        assertNull(raw["detail"])
        assertNull(raw["telegramSuppressed"])
        assertEquals(0, (raw["onlinePlayers"] as JsonPrimitive).content.toInt())
    }
}
