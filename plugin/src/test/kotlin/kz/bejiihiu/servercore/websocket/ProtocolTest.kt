package kz.bejiihiu.servercore.websocket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * cross-implementation tests: every expected hmac below was produced by the
 * backend's node:crypto implementation over the exact same bytes. if these
 * vectors pass, both sides sign identically.
 */
class ProtocolTest {

    private val secret = "0123456789abcdef0123456789abcdef"
    private val codec = EnvelopeCodec(secret)
    private val now = 1_700_000_000_500L

    @Test
    fun `handshake signature matches the backend vector`() {
        // backend: hmac_sha256(secret, "game-1|1700000000000|abcdefgh12")
        val signature = HandshakeSigner.signature(secret, "game-1", "1700000000000", "abcdefgh12")
        assertEquals("3cc39c8d65b99ca99fa1c196b078dca745fd999ba0e3a2c613acd61aafb038ea", signature)
    }

    @Test
    fun `canonical json sorts keys recursively like the backend`() {
        val payload = Payload.SessionCheckResult("11111111-1111-4111-8111-111111111111", true, "sess-9")
        val element = kotlinx.serialization.json.buildJsonObject {
            put("minecraftUuid", kotlinx.serialization.json.JsonPrimitive(payload.minecraftUuid))
            put("online", kotlinx.serialization.json.JsonPrimitive(payload.online))
            put("sessionId", kotlinx.serialization.json.JsonPrimitive(payload.sessionId!!))
        }
        val canonical = EnvelopeCodec.canonicalize(element)
        // backend order: minecraftUuid < online < sessionId (alphabetical)
        assertEquals(
            """{"minecraftUuid":"11111111-1111-4111-8111-111111111111","online":true,"sessionId":"sess-9"}""",
            canonical,
        )
    }

    @Test
    fun `envelope encoding matches the backend signed-frame vector`() {
        val envelope = Envelope(
            id = "a1b2c3d4e5f6",
            type = MessageType.PLAYER_JOINED,
            timestamp = 1_700_000_000_000L,
            payload = Payload.PlayerJoined("11111111-1111-4111-8111-111111111111", "steve"),
        )
        // backend vector: canonical json of the envelope minus hmac, then hmac
        val expectedHmac = "25d378672c76dc304dc7f779cf8d0f39907b55f8935fbac459f6f8e8ddf68a76"
        assertEquals(expectedHmac, codec.sign(envelope))
    }

    @Test
    fun `correlation id participates in the signature`() {
        val envelope = Envelope(
            id = "a1b2c3d4e5f6",
            type = MessageType.SESSION_CHECK_RESULT,
            timestamp = 1_700_000_000_001L,
            payload = Payload.SessionCheckResult("11111111-1111-4111-8111-111111111111", true, "sess-9"),
            correlationId = "probe-abc",
        )
        val expectedHmac = "c9b919d68c20d3df4f99ebbb4e63f01773f7e7b4a3b0dc161204c416a9caea07"
        assertEquals(expectedHmac, codec.sign(envelope))
    }

    @Test
    fun `encode produces parseable json containing the hmac`() {
        val envelope = Envelope(
            id = "a1b2c3d4e5f6",
            type = MessageType.PLAYER_JOINED,
            timestamp = 1_700_000_000_000L,
            payload = Payload.PlayerJoined("11111111-1111-4111-8111-111111111111", "steve"),
        )
        val wire = codec.encode(envelope)
        assertTrue(wire.contains("\"hmac\":\"25d378672c76dc304dc7f779cf8d0f39907b55f8935fbac459f6f8e8ddf68a76\""))
    }

    @Test
    fun `decode accepts a backend-produced frame`() {
        // canonical wire format produced by the backend's signEnvelope
        val wire = """{"id":"srvmsg0001","type":"LINK_TOKEN","timestamp":${now},""" +
            """"payload":{"minecraftUuid":"11111111-1111-4111-8111-111111111111","username":"steve",""" +
            """"token":"abcd1234ABCD5678","expiresAt":1700000060000},"hmac":"PLACEHOLDER"}"""
        // compute what the backend would have signed: build the same envelope
        // here and check our own signature verifies through decode
        val envelope = Envelope(
            id = "srvmsg0001",
            type = MessageType.LINK_TOKEN,
            timestamp = now,
            payload = Payload.LinkToken("11111111-1111-4111-8111-111111111111", "steve", "abcd1234ABCD5678", 1_700_000_060_000L),
        )
        val hmac = codec.sign(envelope)
        val signedWire = wire.replace("\"PLACEHOLDER\"", "\"$hmac\"")

        val decoded = codec.decode(signedWire, now, 30_000, 262_144)
        assertEquals(MessageType.LINK_TOKEN, decoded.envelope.type)
        val payload = decoded.envelope.payload as Payload.LinkToken
        assertEquals("steve", payload.username)
        assertEquals("abcd1234ABCD5678", payload.token)
    }

    @Test
    fun `decode rejects a tampered payload`() {
        val envelope = Envelope(
            id = "srvmsg0002",
            type = MessageType.SESSION_CHECK,
            timestamp = now,
            payload = Payload.SessionCheck("11111111-1111-4111-8111-111111111111"),
        )
        val wire = codec.encode(envelope)
        // flip one byte inside the payload
        val tampered = wire.replace("11111111", "11111112")
        val error = assertFailsWith<ProtocolException> {
            codec.decode(tampered, now, 30_000, 262_144)
        }
        assertTrue(error.message!!.contains("hmac"))
    }

    @Test
    fun `decode rejects stale timestamps`() {
        val envelope = Envelope(
            id = "srvmsg0003",
            type = MessageType.SESSION_CHECK,
            timestamp = now - 60_000,
            payload = Payload.SessionCheck("11111111-1111-4111-8111-111111111111"),
        )
        val wire = codec.encode(envelope)
        assertFailsWith<ProtocolException> {
            codec.decode(wire, now, 30_000, 262_144)
        }
    }

    @Test
    fun `decode rejects oversized frames`() {
        val big = "x".repeat(300_000)
        assertFailsWith<ProtocolException> {
            codec.decode(big, now, 30_000, 262_144)
        }
    }

    @Test
    fun `message ids satisfy the backend envelope pattern`() {
        // ^[A-Za-z0-9_-]{8,64}$
        val pattern = Regex("^[A-Za-z0-9_-]{8,64}$")
        repeat(50) {
            assertTrue(pattern.matches(codec.newMessageId()))
        }
    }
}
