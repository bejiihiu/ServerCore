package kz.bejiihiu.servercore.locale

import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.bejiihiu.servercore.websocket.MessageType
import kz.bejiihiu.servercore.ws.BackendLink
import kz.bejiihiu.servercore.ws.WsEnvelope
import kz.bejiihiu.servercore.ws.decodePayload
import kz.bejiihiu.servercore.ws.messageEnvelope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * the locale channel's plugin half: LOCALE_PUSH must land in the live store
 * and produce a LOCALE_ACK carrying the push's correlation id, and a rejected
 * push must leave the running locale untouched.
 */
class LocalePushListenerTest {

    private val logger = Logger.getLogger("test")

    @TempDir
    lateinit var temp: Path

    private class RecordingLink : BackendLink {
        val sent = mutableListOf<WsEnvelope>()
        var up = true

        override val connected: Boolean
            get() = up

        override fun send(envelope: WsEnvelope): Boolean {
            if (!up) return false
            sent += envelope
            return true
        }
    }

    private fun harness(): Pair<LocaleService, RecordingLink> {
        val service = LocaleService(temp, logger)
        return service to RecordingLink()
    }

    private fun push(
        locale: String = "ru",
        revision: String = "rev-42",
        content: String = "greeting:\n  line: \"<info>привет</info>\"\n",
        correlationId: String = "corr12345678",
    ): WsEnvelope = messageEnvelope(
        LocaleProtocol.TYPE_LOCALE_PUSH,
        LocalePushPayload(locale, revision, content),
        correlationId,
    )

    @Test
    fun `a pushed bundle is applied and acknowledged`() {
        val (service, link) = harness()
        val listener = LocalePushListener(service, link, logger)

        listener.onMessage(push())

        assertEquals("rev-42", service.revision)
        assertEquals("<info>привет</info>", service.text("greeting.line"))
        assertTrue(Files.exists(temp.resolve("locales/ru.yml")))

        val ackEnvelope = link.sent.single()
        assertEquals(LocaleProtocol.TYPE_LOCALE_ACK, ackEnvelope.type)
        assertEquals("corr12345678", ackEnvelope.correlationId)
        val ack = ackEnvelope.decodePayload<LocaleAckPayload>()
        assertTrue(ack.accepted)
        assertEquals("ru", ack.locale)
        assertEquals("rev-42", ack.revision)
        assertNull(ack.reason)
    }

    @Test
    fun `a rejected push keeps the live store and reports why`() {
        val (service, link) = harness()
        val listener = LocalePushListener(service, link, logger)
        val bundledRevision = service.revision

        listener.onMessage(push(locale = "de"))

        assertEquals(bundledRevision, service.revision)
        val ack = link.sent.single().decodePayload<LocaleAckPayload>()
        assertFalse(ack.accepted)
        assertEquals("de", ack.locale)
        assertTrue(ack.reason!!.contains("unsupported"))
    }

    @Test
    fun `an en bundle is applied to the english store`() {
        val (service, link) = harness()
        val listener = LocalePushListener(service, link, logger)

        listener.onMessage(
            push(locale = "en", content = "greeting:\n  line: \"<info>new wave</info>\"\n"),
        )

        service.setLanguage("en")
        assertEquals("<info>new wave</info>", service.text("greeting.line"))
        val ack = link.sent.single().decodePayload<LocaleAckPayload>()
        assertTrue(ack.accepted)
        assertEquals("en", ack.locale)
        assertTrue(Files.exists(temp.resolve("locales/en.yml")))
    }

    @Test
    fun `an unparseable bundle is rejected and the seed still serves`() {
        val (service, link) = harness()
        val listener = LocalePushListener(service, link, logger)

        listener.onMessage(push(content = "tablist: [unclosed"))

        assertFalse(service.snapshot().isEmpty())
        val ack = link.sent.single().decodePayload<LocaleAckPayload>()
        assertFalse(ack.accepted)
        assertTrue(ack.reason!!.startsWith("yaml parse failed"))
    }

    @Test
    fun `other wire types are ignored`() {
        val (service, link) = harness()
        val listener = LocalePushListener(service, link, logger)

        listener.onMessage(
            WsEnvelope(
                id = "msg_12345678",
                type = MessageType.APPLICATION_APPROVED.wire,
                timestamp = System.currentTimeMillis(),
                payload = buildJsonObject { put("applicationId", "507f1f77bcf86cd799439011") },
            ),
        )

        assertTrue(link.sent.isEmpty())
        assertEquals("bundled", service.revision)
    }

    @Test
    fun `a down link does not fake an ack`() {
        val (service, link) = harness()
        link.up = false
        val listener = LocalePushListener(service, link, logger)

        listener.onMessage(push())

        // the push still applies locally, only the ack cannot travel
        assertEquals("rev-42", service.revision)
        assertTrue(link.sent.isEmpty())
    }

    @Test
    fun `LOCALE_ACK is a wire type the transport knows`() {
        assertEquals(MessageType.LOCALE_ACK, MessageType.fromWire(LocaleProtocol.TYPE_LOCALE_ACK))
        assertEquals(MessageType.LOCALE_PUSH, MessageType.fromWire(LocaleProtocol.TYPE_LOCALE_PUSH))
    }

    @Test
    fun `an accepted push re-renders the ambient surfaces, a rejected one does not`() {
        var applied = 0
        val (service, link) = harness()
        val listener = LocalePushListener(service, link, logger) { applied++ }

        listener.onMessage(push(locale = "de"))
        assertEquals(0, applied, "a rejected push must not repaint the tab list")

        listener.onMessage(push())
        assertEquals(1, applied)
    }
}
