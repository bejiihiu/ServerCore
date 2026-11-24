package kz.bejiihiu.servercore.websocket

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class RecordedFrame(val wireType: String, val payload: JsonObject, val correlationId: String?)

/** everything the state machine tests need, no bukkit, no network */
private class Harness(dir: java.nio.file.Path) {
    val clock = AtomicLong(1_700_000_000_000)
    val scheduled = mutableListOf<Pair<Runnable, Long>>()
    val connectedEvents = AtomicInteger(0)
    val disconnectedEvents = AtomicInteger(0)
    val linkTokens = mutableListOf<Payload.LinkToken>()
    val rawFrames = mutableListOf<RecordedFrame>()

    val outbox = DurableOutbox(dir.resolve("outbox.json"), Runnable::run)
    val codec = EnvelopeCodec(SECRET)

    val listener = object : GatewayListener {
        override fun onConnected() {
            connectedEvents.incrementAndGet()
        }

        override fun onDisconnected() {
            disconnectedEvents.incrementAndGet()
        }

        override fun onLinkToken(payload: Payload.LinkToken) {
            linkTokens.add(payload)
        }

        override fun onWireMessage(wireType: String, payload: JsonObject, correlationId: String?, timestamp: Long) {
            rawFrames.add(RecordedFrame(wireType, payload, correlationId))
        }
    }

    val client = GatewayClient(
        config = GatewayConfig(
            url = "ws://127.0.0.1:9/ws",
            serverId = "game-1",
            secret = SECRET,
            ackTimeoutMs = 10_000,
            ackMaxRetries = 2,
            livenessTimeoutMs = 45_000,
        ),
        listener = listener,
        scheduler = { task, delayMs -> scheduled.add(task to delayMs) },
        stats = object : ServerStatsProvider {
            override fun onlinePlayers() = 3
            override fun maxPlayers() = 20
            override fun serverVersion() = "1.21"
        },
        presenceLookup = { true },
        durablePark = outbox,
        now = { clock.get() },
    )

    fun guestPayload(sessionId: String): JsonObject = buildJsonObject {
        put("minecraftUuid", "11111111-1111-4111-8111-111111111111")
        put("username", "steve")
        put("sessionId", sessionId)
        put("snapshot", buildJsonObject {
            put("world", "world")
            put("x", 0.0)
            put("y", 64.0)
            put("z", 0.0)
            put("yaw", 0.0)
            put("pitch", 0.0)
            put("gameMode", "SURVIVAL")
            put("flying", false)
            put("flySpeed", 0.1)
            put("walkSpeed", 0.2)
        })
    }

    /** signs an inbound backend push the backend would have produced */
    fun backendFrame(type: MessageType, payload: Payload, id: String): String =
        codec.encode(Envelope(id, type, clock.get(), payload))

    fun lastScheduledDelay(): Long = scheduled.last().second

    companion object {
        const val SECRET = "0123456789abcdef0123456789abcdef"
    }
}

class GatewayClientResilienceTest {

    private val dir = Files.createTempDirectory("servercore-gw-test")
    private val io = Runnable::run

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        Files.deleteIfExists(dir)
    }

    private fun harness() = Harness(dir).also { it.outbox.load() }

    @Test
    fun `reconnect schedules backoff and onOpen resets the attempt counter`() {
        val h = harness()
        val first = FakeWebSocket()
        h.client.attachForTest(first)
        h.client.onOpen(first)
        assertEquals(1, h.connectedEvents.get())

        h.client.onError(first, RuntimeException("boom"))
        assertEquals(1, h.disconnectedEvents.get())
        val firstDelay = h.lastScheduledDelay()
        assertTrue(firstDelay in 800..1_200, "first reconnect delay $firstDelay")

        // a second failure without a successful connect in between
        val second = FakeWebSocket()
        h.client.attachForTest(second)
        h.client.onError(second, RuntimeException("boom again"))
        val secondDelay = h.lastScheduledDelay()
        assertTrue(secondDelay in 1_600..2_400, "second reconnect delay $secondDelay")

        // a successful connection resets the ladder
        val third = FakeWebSocket()
        h.client.attachForTest(third)
        h.client.onOpen(third)
        assertEquals(2, h.connectedEvents.get())
        h.client.onError(third, RuntimeException("boom again"))
        assertTrue(h.lastScheduledDelay() in 800..1_200, "reset failed")
    }

    @Test
    fun `durable frame parked while offline is replayed on connect and retired on ack`() {
        val h = harness()
        h.client.sendRaw("guestfrm001", "GUEST_STARTED", h.guestPayload("guest-a"), null)
        assertEquals(1, h.outbox.size())

        val socket = FakeWebSocket()
        h.client.attachForTest(socket)
        h.client.onOpen(socket)
        // hello first, then the parked frame
        assertEquals(2, socket.sent.size)
        assertTrue(socket.sent[0].contains("SERVER_STATUS"))
        assertTrue(socket.sent[1].contains("GUEST_STARTED"))

        val ack = h.backendFrame(MessageType.ACK, Payload.Ack("guestfrm001"), "ackmsg00001")
        h.client.onText(socket, ack, true)
        assertEquals(0, h.outbox.size())
    }

    @Test
    fun `an ack retires the parked frame even when it is not in flight`() {
        // regression: a retry tick used to remove the in-flight entry before
        // re-adding it, and an ack landing in that window found nothing to
        // remove, so the durable copy was never unparked and replayed on
        // every reconnect forever. the ack is authoritative either way.
        val h = harness()
        h.client.sendRaw("guestfrm003", "GUEST_STARTED", h.guestPayload("guest-c"), null)
        assertEquals(1, h.outbox.size())

        // frame is parked but never sent, so no in-flight entry exists; the
        // ack must still retire the parked copy
        val socket = FakeWebSocket()
        h.client.attachForTest(socket)
        val ack = h.backendFrame(MessageType.ACK, Payload.Ack("guestfrm003"), "ackmsg00003")
        h.client.onText(socket, ack, true)
        assertEquals(0, h.outbox.size())
    }

    @Test
    fun `presence frames queue in memory, heartbeat frames are not parked at all`() {
        val h = harness()
        h.client.send(MessageType.PLAYER_JOINED, Payload.PlayerJoined("11111111-1111-4111-8111-111111111111", "steve"))
        h.client.send(MessageType.SERVER_HEARTBEAT, Payload.ServerHeartbeat(3, 20))

        // join is queued for replay, the stale heartbeat is dropped silently
        assertEquals(0, h.outbox.size())
        val socket = FakeWebSocket()
        h.client.attachForTest(socket)
        h.client.onOpen(socket)
        // hello + the parked join, no heartbeat replay
        assertEquals(2, socket.sent.size)
        assertTrue(socket.sent[1].contains("PLAYER_JOINED"))
    }

    @Test
    fun `ack timeout retries a bounded number of times then parks durably`() {
        val h = harness()
        val socket = FakeWebSocket()
        h.client.attachForTest(socket)
        h.client.onOpen(socket)
        val baseSent = socket.sent.size

        h.client.sendRaw("endedfrm001", "GUEST_ENDED", buildJsonObject {
            put("minecraftUuid", "11111111-1111-4111-8111-111111111111")
            put("sessionId", "guest-a")
            put("reason", "LOGOUT")
        }, null)
        assertEquals(baseSent + 1, socket.sent.size)

        // first timeout: retry one
        h.clock.addAndGet(10_000)
        h.client.watchdogTick()
        assertEquals(baseSent + 2, socket.sent.size)

        // second timeout: retry two
        h.clock.addAndGet(10_000)
        h.client.watchdogTick()
        assertEquals(baseSent + 3, socket.sent.size)

        // third timeout: retries are over, the frame parks for good
        h.clock.addAndGet(10_000)
        h.client.watchdogTick()
        assertEquals(baseSent + 3, socket.sent.size)
        assertEquals(1, h.outbox.size())
        assertEquals("GUEST_ENDED|guest-a", h.outbox.snapshot().single().key)

        // and the parked frame is not re-parked on the next tick either
        h.clock.addAndGet(10_000)
        h.client.watchdogTick()
        assertEquals(1, h.outbox.size())
    }

    @Test
    fun `ephemeral frames are dropped after the retries, not parked`() {
        val h = harness()
        val socket = FakeWebSocket()
        h.client.attachForTest(socket)
        h.client.onOpen(socket)
        val baseSent = socket.sent.size

        h.client.send(MessageType.PLAYER_JOINED, Payload.PlayerJoined("11111111-1111-4111-8111-111111111111", "steve"))
        repeat(3) {
            h.clock.addAndGet(10_000)
            h.client.watchdogTick()
        }
        // sent once + two retries, then gone: the next player event re-announces
        assertEquals(baseSent + 3, socket.sent.size)
        assertEquals(0, h.outbox.size())
    }

    @Test
    fun `a redelivered backend push is acked but processed exactly once`() {
        val h = harness()
        val socket = FakeWebSocket()
        h.client.attachForTest(socket)
        h.client.onOpen(socket)

        val approved = buildJsonObject {
            put("minecraftUuid", "11111111-1111-4111-8111-111111111111")
            put("sessionId", "auth-abc123")
        }
        val wire = h.backendFrame(MessageType.AUTH_APPROVED, Payload.Raw("AUTH_APPROVED", approved), "bpush00001")
        h.client.onText(socket, wire, true)
        h.client.onText(socket, wire, true)

        assertEquals(1, h.rawFrames.size)
        assertEquals("AUTH_APPROVED", h.rawFrames.single().wireType)
        // both deliveries acked, the backend's outbox can retire the push
        assertEquals(2, socket.sent.count { it.contains(""""ackedId":"bpush00001"""") })
    }

    @Test
    fun `typed listeners still receive their frames alongside the raw fanout`() {
        val h = harness()
        val socket = FakeWebSocket()
        h.client.attachForTest(socket)
        h.client.onOpen(socket)

        val token = h.backendFrame(
            MessageType.LINK_TOKEN,
            Payload.LinkToken("11111111-1111-4111-8111-111111111111", "steve", "abcd1234ABCD5678", h.clock.get() + 60_000),
            "linktok001",
        )
        h.client.onText(socket, token, true)
        assertEquals(1, h.linkTokens.size)
        assertEquals(1, h.rawFrames.size)
        assertEquals("LINK_TOKEN", h.rawFrames.single().wireType)
    }

    @Test
    fun `a silent connection is killed by the liveness watchdog`() {
        val h = harness()
        val socket = FakeWebSocket()
        h.client.attachForTest(socket)
        h.client.onOpen(socket)
        assertEquals(0, h.disconnectedEvents.get())

        // nothing inbound for 46s while the client thinks it is connected
        h.clock.addAndGet(46_000)
        h.client.watchdogTick()

        assertEquals(1, h.disconnectedEvents.get())
        assertTrue(h.lastScheduledDelay() >= 800)
        assertFalse(h.client.isConnected())
    }

    @Test
    fun `a failing send mid-flight re-parks the frame and reconnects`() {
        val h = harness()
        val bad = FakeWebSocket(failSends = true)
        h.client.attachForTest(bad)
        h.client.onOpen(bad)
        assertEquals(1, h.connectedEvents.get())

        h.client.sendRaw("guestfrm002", "GUEST_STARTED", h.guestPayload("guest-b"), null)

        // the frame survived the dead socket and the reconnect was scheduled
        assertEquals(1, h.outbox.size())
        assertEquals(1, h.disconnectedEvents.get())
        assertFalse(h.client.isConnected())
    }

    @Test
    fun `unknown wire types are rejected by sendRaw`() {
        val h = harness()
        assertFalse(h.client.sendRaw("rawmsg00001", "WARP_DRIVE", buildJsonObject { put("x", 1) }, null))
        assertEquals(0, h.outbox.size())
    }

    @Test
    fun `stop re-parks unacked durable frames and closes gracefully instead of aborting`() {
        // regression: stop() used to abort the socket outright, discarding the
        // pending writes and never parking the stranded in-flight frames - a
        // GUEST_ENDED inside the ack window was lost for good on every restart
        val h = harness()
        val socket = FakeWebSocket()
        h.client.attachForTest(socket)
        h.client.onOpen(socket)

        h.client.sendRaw("guestfrm009", "GUEST_ENDED", buildJsonObject {
            put("minecraftUuid", "11111111-1111-4111-8111-111111111111")
            put("sessionId", "guest-a")
            put("reason", "LOGOUT")
        }, null)
        assertTrue(socket.sent.any { it.contains("GUEST_ENDED") })

        h.client.stop()

        // the stranded durable frame is back in the park for the next boot's
        // replay, and the socket got a queued close (flush) rather than a bare
        // abort
        assertEquals(1, h.outbox.size())
        assertEquals("GUEST_ENDED|guest-a", h.outbox.snapshot().single().key)
        assertTrue(socket.closeRequested)
    }
}
