package kz.bejiihiu.servercore.placeholderapi

import java.util.UUID
import kz.bejiihiu.servercore.ws.ApplicationExpiredPayload
import kz.bejiihiu.servercore.ws.ApplicationSubmittedPayload
import kz.bejiihiu.servercore.ws.MessageTypes
import kz.bejiihiu.servercore.ws.WsEnvelope
import kz.bejiihiu.servercore.ws.messageEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * the ws half of the placeholder pipeline: backend events that reach the
 * gateway hub end up as cache facts, including the APPLICATION_EXPIRED
 * verdict that rides the raw fan-out exactly like the typed ones.
 */
class StateWsListenerTest {

    private val uuid = UUID.fromString("11111111-1111-4111-8111-111111111111")

    private inline fun <reified T> envelope(type: String, payload: T): WsEnvelope =
        messageEnvelope(type, payload)

    @Test
    fun `application verdicts drive the cache through the listener`() {
        val cache = ServerStateCache()
        val listener = StateWsListener(cache)

        listener.onMessage(
            envelope(
                MessageTypes.APPLICATION_SUBMITTED,
                ApplicationSubmittedPayload(applicationId = "a".repeat(24), minecraftUuid = uuid.toString(), telegramId = "100"),
            ),
        )
        assertEquals(ServerStateCache.APP_VOTING, cache.snapshot(uuid)!!.applicationStatus)

        listener.onMessage(
            envelope(
                MessageTypes.APPLICATION_EXPIRED,
                ApplicationExpiredPayload(applicationId = "a".repeat(24), minecraftUuid = uuid.toString()),
            ),
        )
        assertEquals(ServerStateCache.APP_EXPIRED, cache.snapshot(uuid)!!.applicationStatus)
    }

    @Test
    fun `a malformed payload is dropped without touching the cache`() {
        val cache = ServerStateCache()
        val listener = StateWsListener(cache)

        listener.onMessage(
            WsEnvelope(
                id = "0123456789abcdef",
                type = MessageTypes.APPLICATION_EXPIRED,
                timestamp = 0L,
                correlationId = null,
                payload = kotlinx.serialization.json.buildJsonObject { },
            ),
        )

        assertNull(cache.snapshot(uuid))
    }
}
