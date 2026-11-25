package kz.bejiihiu.servercore.websocket

import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * acks are the only thing that retires a frame, and they arrive on a thread
 * that knows nothing about the send that produced them. this suite throws the
 * hostile orderings a real gateway produces at the client: duplicated acks,
 * acks that arrive out of order, acks for frames that are already parked,
 * reconnect storms and send futures that fail *after* the disconnect sweep
 * already collected the frame. the invariant throughout: replay is
 * exactly-once, a swallowed ack never leaves a frame parked forever and a
 * duplicate never sends a second copy.
 */
class GatewayAckChaosTest {

    private val dir = Files.createTempDirectory("servercore-gw-ack")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        Files.deleteIfExists(dir)
    }

    /** restart simulation: same outbox file, fresh in-memory client */
    private fun harness() = GatewayTestHarness(dir).also { it.outbox.load() }

    /**
     * a socket whose writes stay pending until the test settles them, like a
     * real network buffer that only reports the failure after the read side
     * already noticed the connection is gone
     */
    private class PendingWebSocket : WebSocket {
        val sent = mutableListOf<String>()
        private val pending = mutableListOf<CompletableFuture<WebSocket>>()

        override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
            sent.add(data.toString())
            val future = CompletableFuture<WebSocket>()
            pending.add(future)
            return future
        }

        fun failAll() {
            val copies = ArrayList(pending)
            pending.clear()
            copies.forEach { it.completeExceptionally(RuntimeException("connection reset")) }
        }

        private fun completed(): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)

        override fun sendBinary(data: ByteBuffer, last: Boolean): CompletableFuture<WebSocket> = completed()
        override fun sendPing(message: ByteBuffer): CompletableFuture<WebSocket> = completed()
        override fun sendPong(message: ByteBuffer): CompletableFuture<WebSocket> = completed()
        override fun sendClose(statusCode: Int, reason: String?): CompletableFuture<WebSocket> = completed()
        override fun request(n: Long) {}
        override fun getSubprotocol(): String = ""
        override fun isOutputClosed(): Boolean = false
        override fun isInputClosed(): Boolean = false
        override fun abort() {}
    }

    private fun ackFrame(h: GatewayTestHarness, id: String, ackId: String = "ackmsg$id"): String =
        h.backendFrame(MessageType.ACK, Payload.Ack(id), ackId)

    @Test
    fun `duplicate and out-of-order acks retire exactly the frames they name`() {
        val h = harness()
        val socket = FakeWebSocket()
        h.client.attachForTest(socket)
        h.client.onOpen(socket)

        val ids = listOf("chaosfrm001", "chaosfrm002", "chaosfrm003")
        ids.forEach { id -> h.client.sendRaw(id, "GUEST_STARTED", h.guestPayload("guest-$id"), null) }
        // a live link keeps unacked frames in flight, nothing is parked yet
        assertEquals(0, h.outbox.size())

        // the newest frame is acked first, then the oldest, then both again
        h.client.onText(socket, ackFrame(h, ids[2]), true)
        h.client.onText(socket, ackFrame(h, ids[0]), true)
        h.client.onText(socket, ackFrame(h, ids[2]), true)
        h.client.onText(socket, ackFrame(h, ids[0]), true)
        assertEquals(0, h.outbox.size())

        // the link dies with one unacked frame: exactly that one is parked
        h.client.onError(socket, RuntimeException("reset"))
        assertEquals(listOf(ids[1]), h.outbox.snapshot().map { it.id })

        // an ack for an id this process never sent must not evict neighbours
        h.client.onText(socket, ackFrame(h, "never-sent-0001"), true)
        assertEquals(listOf(ids[1]), h.outbox.snapshot().map { it.id })

        // and a duplicate of an already-retired ack never resurrects anything
        h.client.onText(socket, ackFrame(h, ids[0]), true)
        assertEquals(listOf(ids[1]), h.outbox.snapshot().map { it.id })

        h.client.onText(socket, ackFrame(h, ids[1]), true)
        assertEquals(0, h.outbox.size())
    }

    @Test
    fun `an ack that lands after the retry budget still ends the lifecycle`() {
        val h = harness()
        val socket = FakeWebSocket()
        h.client.attachForTest(socket)
        h.client.onOpen(socket)

        h.client.sendRaw("retryfrm0001", "GUEST_ENDED", h.endedPayload("guest-r"), null)
        val afterSend = socket.sent.size

        // the ack is slow: the watchdog retries once before it shows up
        h.clock.addAndGet(10_000)
        h.client.watchdogTick()
        assertEquals(afterSend + 1, socket.sent.size)

        h.client.onText(socket, ackFrame(h, "retryfrm0001"), true)
        assertEquals(0, h.outbox.size())

        // the late ack also stops the retry ladder: no further ticks resend
        h.clock.addAndGet(10_000)
        h.client.watchdogTick()
        h.client.watchdogTick()
        assertEquals(afterSend + 1, socket.sent.size)
        assertEquals(0, h.outbox.size())
    }

    @Test
    fun `a reconnect storm schedules one retry per socket, not one per failure`() {
        val h = harness()
        val socket = FakeWebSocket()
        h.client.attachForTest(socket)
        h.client.onOpen(socket)

        // a real socket reports the same close through onError and onClose, and
        // a failing send can add one more: all of them are the same disconnect
        repeat(20) { h.client.onError(socket, RuntimeException("reset $it")) }
        h.client.onClose(socket, 1006, "abnormal")

        assertEquals(1, h.disconnectedEvents.get(), "one disconnect event per dead socket")
        assertEquals(1, h.scheduled.size, "one reconnect attempt per dead socket")
        assertFalse(h.client.isConnected())

        // and the storm did not duplicate anything that was parked
        assertEquals(0, h.outbox.size())
    }

    @Test
    fun `a send failure that lands after the disconnect sweep parks a durable frame once`() {
        val h = harness()
        val socket = PendingWebSocket()
        h.client.attachForTest(socket)
        h.client.onOpen(socket)

        h.client.sendRaw("latefail0001", "GUEST_STARTED", h.guestPayload("guest-l"), null)
        assertEquals(0, h.outbox.size(), "nothing is parked while the write is still pending")

        // read side notices the drop first: the frame is swept into the park...
        h.client.onError(socket, RuntimeException("connection reset"))
        assertEquals(listOf("latefail0001"), h.outbox.snapshot().map { it.id })

        // ...and only then does the write future report its failure
        socket.failAll()

        // parking twice under the same idempotency key must stay one frame
        assertEquals(1, h.outbox.size(), "upsert by key keeps exactly one parked copy")

        // the replayed connection sends the frame exactly once
        val next = FakeWebSocket()
        h.client.attachForTest(next)
        h.client.onOpen(next)
        assertEquals(1, next.sent.count { it.contains("latefail0001") })
    }

    @Test
    fun `a send failure that lands after the disconnect sweep queues an ephemeral frame once`() {
        val h = harness()
        val socket = PendingWebSocket()
        h.client.attachForTest(socket)
        h.client.onOpen(socket)

        h.client.send(
            MessageType.PLAYER_JOINED,
            Payload.PlayerJoined("11111111-1111-4111-8111-111111111111", "steve"),
        )
        h.client.onError(socket, RuntimeException("connection reset"))
        socket.failAll()

        // the in-memory queue has no upsert key, so the duplicate is caught by
        // envelope id: replaying one frame twice re-sends what the peer has
        val next = FakeWebSocket()
        h.client.attachForTest(next)
        h.client.onOpen(next)
        assertEquals(1, next.sent.count { it.contains("PLAYER_JOINED") }, "queued frame replayed exactly once")
    }

    @Test
    fun `a frame parked by the previous run is replayed once and never again`() {
        // boot one: the link is down, the guest event parks and the plugin dies
        val first = harness()
        first.client.sendRaw("restartfrm01", "GUEST_STARTED", first.guestPayload("guest-x"), null)
        first.client.sendRaw("restartfrm02", "GUEST_ENDED", first.endedPayload("guest-y"), null)
        first.outbox.flushNow()

        // boot two: replay in park order, the backend acks both
        val second = harness()
        assertEquals(listOf("restartfrm01", "restartfrm02"), second.outbox.snapshot().map { it.id })
        val socket = FakeWebSocket()
        second.client.attachForTest(socket)
        second.client.onOpen(socket)
        assertEquals(1, socket.sent.count { it.contains("restartfrm01") })
        assertEquals(1, socket.sent.count { it.contains("restartfrm02") })
        // chronological: the started frame goes out before the ended one
        assertTrue(
            socket.sent.indexOfFirst { it.contains("restartfrm01") } <
                socket.sent.indexOfFirst { it.contains("restartfrm02") },
        )

        second.client.onText(socket, ackFrame(second, "restartfrm01"), true)
        second.client.onText(socket, ackFrame(second, "restartfrm02"), true)
        assertEquals(0, second.outbox.size())
        second.outbox.flushNow()

        // boot three: nothing left to replay
        val third = harness()
        assertEquals(0, third.outbox.size())
        val thirdSocket = FakeWebSocket()
        third.client.attachForTest(thirdSocket)
        third.client.onOpen(thirdSocket)
        assertEquals(0, thirdSocket.sent.count { it.contains("GUEST_") })
    }

    @Test
    fun `a reconnect while a durable frame is in flight re-parks it for the next link`() {
        val h = harness()
        val first = FakeWebSocket()
        h.client.attachForTest(first)
        h.client.onOpen(first)
        h.client.sendRaw("midflight001", "GUEST_STARTED", h.guestPayload("guest-m"), null)
        assertEquals(0, h.outbox.size(), "an in-flight frame is not parked while the link is alive")

        // the link dies inside the ack window
        h.client.onError(first, RuntimeException("reset"))
        assertEquals(1, h.outbox.size())

        // the very next connection replays it, and the ack from that link
        // retires it even though the client restarted its attempt ladder
        val second = FakeWebSocket()
        h.client.attachForTest(second)
        h.client.onOpen(second)
        assertEquals(1, second.sent.count { it.contains("midflight001") })
        h.client.onText(second, ackFrame(h, "midflight001"), true)
        assertEquals(0, h.outbox.size())

        // a second reconnect must not replay a retired frame
        h.client.onError(second, RuntimeException("reset again"))
        val third = FakeWebSocket()
        h.client.attachForTest(third)
        h.client.onOpen(third)
        assertEquals(0, third.sent.count { it.contains("midflight001") })
    }

    @Test
    fun `the dedupe window survives a reconnect, so a redelivered push is not re-run`() {
        val h = harness()
        val first = FakeWebSocket()
        h.client.attachForTest(first)
        h.client.onOpen(first)

        val push = h.backendFrame(
            MessageType.AUTH_APPROVED,
            Payload.Raw("AUTH_APPROVED", h.guestPayload("auth-1")),
            "bpushchaos01",
        )
        h.client.onText(first, push, true)
        assertEquals(1, h.rawFrames.size)

        // the backend never saw our ack (link died first) and redelivers the
        // same content under a fresh envelope id on the new link
        h.client.onError(first, RuntimeException("reset"))
        val second = FakeWebSocket()
        h.client.attachForTest(second)
        h.client.onOpen(second)
        val redelivered = h.backendFrame(
            MessageType.AUTH_APPROVED,
            Payload.Raw("AUTH_APPROVED", h.guestPayload("auth-1")),
            "bpushchaos02",
        )
        h.client.onText(second, redelivered, true)

        assertEquals(1, h.rawFrames.size, "content dedupe is per process, not per connection")
        assertTrue(second.sent.any { it.contains(""""ackedId":"bpushchaos02"""") }, "the redelivery is still acked")
    }
}
