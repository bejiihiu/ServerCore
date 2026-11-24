package kz.bejiihiu.servercore.websocket

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * raw fan-out decoding: backend push types the typed layer has no vocabulary
 * for must arrive as raw json instead of killing the connection.
 */
class ProtocolRawFanoutTest {

    private val secret = "0123456789abcdef0123456789abcdef"
    private val codec = EnvelopeCodec(secret)
    private val now = 1_700_000_000_500L
    private val uuid = "11111111-1111-4111-8111-111111111111"

    private fun json(raw: String) = kotlinx.serialization.json.Json.parseToJsonElement(raw) as JsonObject

    /** hmac-sha256 hex, same recipe the codec uses, for frames the codec can't encode */
    private fun hmac(canonical: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** wire frame for a wire type the enum can't carry, signed over its canonical form */
    private fun unknownTypeWire(type: String, payloadJson: String): String {
        val meta = buildJsonObject {
            put("id", "srvmsg0003")
            put("payload", json(payloadJson))
            put("timestamp", now)
            put("type", type)
        }
        val canonical = EnvelopeCodec.canonicalize(meta)
        // append the hmac field; decode re-canonicalizes minus hmac, so key
        // order in the wire itself doesn't matter
        return canonical.dropLast(1) + ",\"hmac\":\"" + hmac(canonical) + "\"}"
    }

    @Test
    fun `auth approved decodes as raw json with the wire type preserved`() {
        val envelope = Envelope(
            id = "srvmsg0001",
            type = MessageType.AUTH_APPROVED,
            timestamp = now,
            payload = Payload.Raw(MessageType.AUTH_APPROVED.wire, json("""{"minecraftUuid":"$uuid","sessionId":"sess-1"}""")),
        )
        val decoded = codec.decode(codec.encode(envelope), now, 30_000, 262_144)
        assertEquals(MessageType.AUTH_APPROVED, decoded.envelope.type)
        val raw = assertIs<Payload.Raw>(decoded.envelope.payload)
        assertEquals("AUTH_APPROVED", raw.wireType)
        assertEquals(uuid, (raw.fields["minecraftUuid"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `application events decode as raw json`() {
        val envelope = Envelope(
            id = "srvmsg0002",
            type = MessageType.APPLICATION_APPROVED,
            timestamp = now,
            payload = Payload.Raw(
                MessageType.APPLICATION_APPROVED.wire,
                json("""{"applicationId":"507f1f77bcf86cd799439011","minecraftUuid":"$uuid"}"""),
            ),
        )
        val decoded = codec.decode(codec.encode(envelope), now, 30_000, 262_144)
        val raw = assertIs<Payload.Raw>(decoded.envelope.payload)
        assertEquals(MessageType.APPLICATION_APPROVED.wire, raw.wireType)
    }

    @Test
    fun `a wire type this build never heard of survives the decode`() {
        // version skew simulation: a newer backend pushing FUTURE_EVENT
        val decoded = codec.decode(
            unknownTypeWire("FUTURE_EVENT", """{"x":1}"""),
            now, 30_000, 262_144,
        )
        assertEquals(MessageType.UNKNOWN, decoded.envelope.type)
        val raw = assertIs<Payload.Raw>(decoded.envelope.payload)
        assertEquals("FUTURE_EVENT", raw.wireType)
        assertTrue(raw.fields.containsKey("x"))
    }

    @Test
    fun `typed frames still decode typed`() {
        val envelope = Envelope(
            id = "srvmsg0004",
            type = MessageType.SESSION_CHECK,
            timestamp = now,
            payload = Payload.SessionCheck(uuid),
        )
        val decoded = codec.decode(codec.encode(envelope), now, 30_000, 262_144)
        assertEquals(MessageType.SESSION_CHECK, decoded.envelope.type)
        assertIs<Payload.SessionCheck>(decoded.envelope.payload)
    }
}
