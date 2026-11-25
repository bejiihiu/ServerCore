package kz.bejiihiu.servercore.websocket

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** one raw inbound frame as a listener saw it */
internal class RecordedFrame(val wireType: String, val payload: JsonObject, val correlationId: String?)

/**
 * everything the client state machine tests need: a controllable clock, a
 * scheduler that only records, a recording listener and a file-backed outbox in
 * the given directory. no bukkit, no network, no sleeping.
 */
internal class GatewayTestHarness(dir: Path) {
    val clock = AtomicLong(1_700_000_000_000)
    val scheduled = mutableListOf<Pair<Runnable, Long>>()
    val connectedEvents = AtomicInteger(0)
    val disconnectedEvents = AtomicInteger(0)
    val linkTokens = mutableListOf<Payload.LinkToken>()
    val rawFrames = mutableListOf<RecordedFrame>()

    val outbox = DurableOutbox(dir.resolve(OUTBOX_FILE), Runnable::run)
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

    fun endedPayload(sessionId: String): JsonObject = buildJsonObject {
        put("minecraftUuid", "11111111-1111-4111-8111-111111111111")
        put("sessionId", sessionId)
        put("reason", "LOGOUT")
    }

    /** signs an inbound backend push the backend would have produced */
    fun backendFrame(type: MessageType, payload: Payload, id: String): String =
        codec.encode(Envelope(id, type, clock.get(), payload))

    fun lastScheduledDelay(): Long = scheduled.last().second

    companion object {
        const val SECRET = "0123456789abcdef0123456789abcdef"
        const val OUTBOX_FILE = "outbox.json"
    }
}
