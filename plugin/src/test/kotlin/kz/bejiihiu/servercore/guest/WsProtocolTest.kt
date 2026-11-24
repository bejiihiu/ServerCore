package kz.bejiihiu.servercore.ws

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kz.bejiihiu.servercore.guest.AccountStateMapper
import kz.bejiihiu.servercore.guest.AccountStatus
import kz.bejiihiu.servercore.guest.CachedAccount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WsProtocolTest {

    @Test
    fun `guest_started payload matches the backend schema field names`() {
        val envelope = messageEnvelope(
            MessageTypes.GUEST_STARTED,
            GuestStartedPayload(
                minecraftUuid = UUID.randomUUID().toString(),
                username = "bejiihiu",
                sessionId = "guest-abc123",
                snapshot = SnapshotPayload(
                    world = "world", x = 1.0, y = 2.0, z = 3.0,
                    yaw = 4.0, pitch = 5.0,
                    gameMode = "SURVIVAL", flying = false,
                    flySpeed = 0.1, walkSpeed = 0.2,
                ),
            ),
        )

        assertEquals("GUEST_STARTED", envelope.type)
        val payload = envelope.payload.jsonObject
        assertEquals(
            setOf("minecraftUuid", "username", "sessionId", "snapshot"),
            payload.keys,
        )
        assertEquals(
            setOf("world", "x", "y", "z", "yaw", "pitch", "gameMode", "flying", "flySpeed", "walkSpeed"),
            payload["snapshot"]!!.jsonObject.keys,
        )
    }

    @Test
    fun `envelope roundtrip keeps correlation id and payload types`() {
        val original = messageEnvelope(
            MessageTypes.AUTH_REQUEST,
            AuthRequestPayload(
                minecraftUuid = UUID.randomUUID().toString(),
                username = "player",
                sessionId = "guest-xyz",
                token = "AbCdEf0123456789",
            ),
            correlationId = "corr-123",
        )
        val text = wsJson.encodeToString(WsEnvelope.serializer(), original)
        val parsed = wsJson.decodeFromString(WsEnvelope.serializer(), text)

        assertEquals(original.id, parsed.id)
        assertEquals(original.type, parsed.type)
        assertEquals(original.timestamp, parsed.timestamp)
        assertEquals(original.correlationId, parsed.correlationId)
        val decoded = parsed.decodePayload<AuthRequestPayload>()
        assertEquals("AbCdEf0123456789", decoded.token)
        assertNull(decoded.link)
    }

    @Test
    fun `unknown backend extensions do not break parsing`() {
        val text = """
            {"id":"abcdef123456","type":"AUTH_REQUEST","timestamp":1,
             "payload":{"minecraftUuid":"00000000-0000-0000-0000-000000000000",
                        "username":"a","sessionId":"s","futureField":42}}
        """.trimIndent()
        val parsed = wsJson.decodeFromString(WsEnvelope.serializer(), text)
        val payload = parsed.decodePayload<AuthRequestPayload>()
        assertEquals("a", payload.username)
    }

    @Test
    fun `session ids satisfy the backend pattern`() {
        val id = newSessionId()
        assertTrue(Regex("^[a-z0-9._-]{1,64}$").matches(id), "session id $id violates the wire pattern")
        assertTrue(Regex("^[A-Za-z0-9_-]{8,64}$").matches(newMessageId()), "message id violates the wire pattern")
    }

    @Test
    fun `account cache json survives a roundtrip`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = CachedAccount(status = "ACTIVE", updatedAt = 1234567890L)
        val encoded = json.encodeToString(CachedAccount.serializer(), original)
        assertEquals(original, json.decodeFromString(CachedAccount.serializer(), encoded))
    }

    @Test
    fun `cached state maps to join decisions`() {
        assertEquals(AccountStatus.Authorized, AccountStateMapper.fromCached("ACTIVE"))
        assertEquals(AccountStatus.Authorized, AccountStateMapper.fromCached("active"))
        assertEquals(AccountStatus.Denied("BANNED"), AccountStateMapper.fromCached("BANNED"))
        assertEquals(AccountStatus.GuestPendingLogin(null), AccountStateMapper.fromCached("VOTING"))
        assertEquals(AccountStatus.GuestPendingLogin(null), AccountStateMapper.fromCached(null))
    }
}
