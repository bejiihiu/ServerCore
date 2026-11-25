package kz.bejiihiu.servercore.websocket

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * a correlated ERROR means "acked, but not applied". the plugin has to notice
 * or a database blip on the backend silently eats a durable frame: the ack
 * retires it and nothing ever replays it. these tests pin the recovery path
 * (re-park, bounded retry under a fresh envelope id) and all of its stops.
 */
class GatewayErrorFrameTest {

    private val dir = Files.createTempDirectory("servercore-gw-error")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        Files.deleteIfExists(dir)
    }

    private fun harness() = GatewayTestHarness(dir).also { it.outbox.load() }

    private fun wireId(wire: String): String =
        Regex("\"id\":\"([A-Za-z0-9_-]+)\"").find(wire)?.groupValues?.get(1)
            ?: error("no envelope id in $wire")

    private fun connected(h: GatewayTestHarness): FakeWebSocket {
        val socket = FakeWebSocket()
        h.client.attachForTest(socket)
        h.client.onOpen(socket)
        return socket
    }

    private fun ack(h: GatewayTestHarness, socket: FakeWebSocket, id: String) {
        h.client.onText(socket, h.backendFrame(MessageType.ACK, Payload.Ack(id), "ackmsg$id"), true)
    }

    private fun error(
        h: GatewayTestHarness,
        socket: FakeWebSocket,
        code: String,
        inReplyTo: String?,
        message: String = "backend could not process this frame",
    ) {
        val frame = h.backendFrame(MessageType.ERROR, Payload.Error(code, message, inReplyTo), "errmsg${h.clock.get()}")
        h.client.onText(socket, frame, true)
    }

    private fun tick(h: GatewayTestHarness) {
        h.clock.addAndGet(1_000)
        h.client.watchdogTick()
    }

    @Test
    fun `a correlated error re-parks a durable frame and retries it under a fresh id`() {
        val h = harness()
        val socket = connected(h)

        h.client.sendRaw("guestfrm021", "GUEST_STARTED", h.guestPayload("guest-err"), null)
        ack(h, socket, "guestfrm021")
        assertEquals(0, h.outbox.size(), "the ack retired the frame before the failure report")

        error(h, socket, "HANDLER_FAILED", "guestfrm021")
        assertEquals(1, h.outbox.size(), "the failed frame is back in the durable outbox")
        val parked = h.outbox.snapshot().single()
        assertEquals("GUEST_STARTED|guest-err", parked.key)
        assertNotEquals("guestfrm021", parked.id, "the retry needs a new identity to be processed")

        val beforeRetry = socket.sent.size
        tick(h)
        assertEquals(beforeRetry + 1, socket.sent.size, "the retry tick resends the frame")
        val retried = socket.sent.last()
        assertTrue(retried.contains("GUEST_STARTED"))
        assertEquals(parked.id, wireId(retried))

        // the ack of the retried copy retires the parked one
        ack(h, socket, parked.id)
        assertEquals(0, h.outbox.size())
    }

    @Test
    fun `the retry fails three times then the frame is dropped and counted`() {
        val h = harness()
        val socket = connected(h)

        h.client.sendRaw("guestfrm022", "GUEST_STARTED", h.guestPayload("guest-cap"), null)
        var id = "guestfrm022"
        val baseSends = socket.sent.size

        repeat(3) { round ->
            ack(h, socket, id)
            error(h, socket, "HANDLER_FAILED", id)
            assertEquals(1, h.outbox.size(), "retry ${round + 1} is parked")
            id = h.outbox.snapshot().single().id
            tick(h)
            assertEquals(baseSends + round + 1, socket.sent.size)
        }

        // the fourth failure is over the budget: logged, counted, not re-parked
        ack(h, socket, id)
        error(h, socket, "HANDLER_FAILED", id)
        assertEquals(0, h.outbox.size(), "a frame over the retry budget is not parked again")
        assertEquals(1L, h.client.droppedAfterBackendError())
        assertTrue(h.logs.any { it.contains("dropping GUEST_STARTED") && it.contains("3 retries") })

        // and no further tick resurrects it
        tick(h)
        tick(h)
        assertEquals(baseSends + 3, socket.sent.size)
        assertEquals(0, h.outbox.size())
    }

    @Test
    fun `the retry budget survives a restart through the outbox`() {
        // boot one left a frame that already burned its retries
        val first = harness()
        first.outbox.park(
            ParkedFrame(
                id = "guestfrm024",
                key = "GUEST_STARTED|guest-tired",
                type = "GUEST_STARTED",
                wire = first.codec.encode(
                    Envelope("guestfrm024", MessageType.GUEST_STARTED, first.clock.get(), Payload.Raw("GUEST_STARTED", first.guestPayload("guest-tired"))),
                ),
                enqueuedAt = first.clock.get(),
                errorAttempts = 3,
            ),
        )

        // boot two replays it, the backend acks and then fails it again
        val second = harness()
        val socket = connected(second)
        val baseSends = socket.sent.size
        assertTrue(socket.sent.any { it.contains("guestfrm024") })
        ack(second, socket, "guestfrm024")
        error(second, socket, "HANDLER_FAILED", "guestfrm024")

        assertEquals(0, second.outbox.size(), "a frame whose budget is spent is not parked again")
        assertEquals(1L, second.client.droppedAfterBackendError())
        tick(second)
        assertEquals(baseSends, socket.sent.size)
    }

    @Test
    fun `a deterministic BAD_PAYLOAD failure is never retried`() {
        val h = harness()
        val socket = connected(h)

        h.client.sendRaw("guestfrm023", "GUEST_STARTED", h.guestPayload("guest-bad"), null)
        ack(h, socket, "guestfrm023")
        val baseSends = socket.sent.size

        error(h, socket, "BAD_PAYLOAD", "guestfrm023", "payload is not canonical-json safe")
        assertEquals(0, h.outbox.size())
        assertEquals(1L, h.client.droppedAfterBackendError())

        tick(h)
        tick(h)
        assertEquals(baseSends, socket.sent.size, "a rejected payload is not resent")
        assertTrue(h.logs.any { it.contains("not retried") })
    }

    @Test
    fun `an error naming a frame this process never sent is ignored`() {
        val h = harness()
        val socket = connected(h)
        val baseSends = socket.sent.size

        error(h, socket, "HANDLER_FAILED", "ghostframe01")
        assertEquals(0, h.outbox.size())
        assertEquals(0L, h.client.droppedAfterBackendError())
        tick(h)
        assertEquals(baseSends, socket.sent.size)
        assertTrue(h.logs.any { it.contains("ghostframe01") && it.contains("ignored") })
    }

    @Test
    fun `an error without inReplyTo is only logged`() {
        val h = harness()
        val socket = connected(h)
        val baseSends = socket.sent.size

        error(h, socket, "BAD_HMAC", null, "signature mismatch")
        assertEquals(0, h.outbox.size())
        assertEquals(0L, h.client.droppedAfterBackendError())
        assertEquals(baseSends, socket.sent.size)
        assertTrue(h.logs.any { it.contains("BAD_HMAC") && it.contains("signature mismatch") })
    }

    @Test
    fun `a queued frame is retried in memory without touching the disk outbox`() {
        val h = harness()
        val socket = connected(h)

        h.client.send(
            MessageType.PLAYER_JOINED,
            Payload.PlayerJoined("11111111-1111-4111-8111-111111111111", "steve"),
        )
        val firstId = wireId(socket.sent.last())
        ack(h, socket, firstId)

        error(h, socket, "HANDLER_FAILED", firstId)
        assertEquals(0, h.outbox.size(), "queued frames are replayable, not durable")

        tick(h)
        assertEquals(2, socket.sent.count { it.contains("PLAYER_JOINED") })
        assertNotEquals(firstId, wireId(socket.sent.last()))
    }

    @Test
    fun `a fire-and-forget frame is only logged, never resent`() {
        val h = harness()
        val socket = connected(h)

        h.client.send(MessageType.SERVER_HEARTBEAT, Payload.ServerHeartbeat(3, 20))
        val id = wireId(socket.sent.last())
        ack(h, socket, id)
        val baseSends = socket.sent.size

        error(h, socket, "HANDLER_FAILED", id)
        tick(h)
        assertEquals(baseSends, socket.sent.size)
        assertEquals(0, h.outbox.size())
        assertEquals(0L, h.client.droppedAfterBackendError())
        assertTrue(h.logs.any { it.contains("HANDLER_FAILED") }, "the failure is visible in the log, not retried")
    }

    @Test
    fun `an incoming error frame is not acked and later pushes still route`() {
        val h = harness()
        val socket = connected(h)

        error(h, socket, "BAD_HMAC", null)
        val push = h.backendFrame(MessageType.AUTH_APPROVED, Payload.Raw("AUTH_APPROVED", h.guestPayload("auth-err")), "bpush50001")
        h.client.onText(socket, push, true)

        assertEquals(1, h.rawFrames.size, "transport errors do not disturb the push fan-out")
        // no ACK for the ERROR itself, exactly one for the push
        assertTrue(socket.sent.none { it.contains(""""ackedId":"errmsg""") })
        assertTrue(socket.sent.any { it.contains(""""ackedId":"bpush50001"""") })
    }
}
