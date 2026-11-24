package kz.bejiihiu.servercore.websocket

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonObject

/** connection knobs, loaded from the plugin config */
data class GatewayConfig(
    /** full ws/wss url including the /ws path */
    val url: String,
    val serverId: String,
    val secret: String,
    val reconnectInitialMs: Long = 1_000,
    val reconnectMaxMs: Long = 60_000,
    val heartbeatIntervalMs: Long = 15_000,
    /** backend timestamp tolerance, mirrors the gateway's replay window */
    val maxSkewMs: Long = 30_000,
    val maxPacketBytes: Int = 262_144,
    /** how long an unacknowledged frame waits before the first retry */
    val ackTimeoutMs: Long = 10_000,
    /** retries after the first timeout, then the frame parks (or dies) for good */
    val ackMaxRetries: Int = 2,
    /**
     * no inbound frame for this long on a "connected" socket means the pipe is
     * gone (half-open), no matter what sendText claims. the backend acks every
     * heartbeat, so a healthy link speaks at least once per heartbeat interval.
     */
    val livenessTimeoutMs: Long = 45_000,
    /** how long a processed inbound frame stays remembered for dedupe */
    val dedupeTtlMs: Long = 600_000,
    /** hard cap of the durable outbox file */
    val outboxMaxEntries: Int = DurableOutbox.DEFAULT_MAX,
)

/** server-side numbers for hello/heartbeat frames, read live at send time */
interface ServerStatsProvider {
    fun onlinePlayers(): Int
    fun maxPlayers(): Int
    fun serverVersion(): String
}

/**
 * what the rest of the plugin gets from the gateway. callbacks arrive on
 * websocket/async threads — never touch bukkit objects from here directly,
 * hand work to the entity scheduler first.
 */
interface GatewayListener {
    fun onConnected() {}
    fun onDisconnected() {}
    fun onLinkToken(payload: Payload.LinkToken) {}
    fun onAccountDeleted(payload: Payload.AccountDeleted) {}
}

/**
 * every validated inbound domain frame, fanned out to the ws layer (guest
 * module hub) as a raw json object. ack/error frames never reach it.
 */
fun interface RawFrameListener {
    fun onFrame(id: String, type: String, payload: JsonObject, correlationId: String?)
}

/**
 * minimal scheduling seam so the client stays bukkit-free and unit-testable;
 * the plugin wires it to folia's AsyncScheduler.
 */
fun interface DelayScheduler {
    fun scheduleDelayed(task: Runnable, delayMs: Long)
}

/** one frame on its way out, kept around until the backend acks it */
private class OutgoingFrame(
    val id: String,
    val type: MessageType,
    val wire: String,
    /** idempotency key for the durable park, computed once at creation */
    val key: String,
    val durability: Durability,
    val enqueuedAt: Long,
)

private class InFlight(
    val frame: OutgoingFrame,
    var attempts: Int,
    var deadline: Long,
)

/**
 * the plugin side of the backend websocket gateway.
 *
 * transport: outbound connection to `GET /ws?server=..&ts=..&nonce=..&sig=..`,
 * hmac over `server|ts|nonce` computed pre-upgrade — an unauthenticated
 * client never becomes a socket at all. every frame after that is
 * envelope-signed with the same secret (see Protocol.kt).
 *
 * reliability: exponential backoff with jitter on every abnormal close,
 * acks for every backend push, ack tracking for our own pushes with bounded
 * retries, a read-idle watchdog that kills half-open connections, receiver
 * dedupe for redelivered backend pushes, an in-memory queue for presence
 * events emitted while the connection was down and a disk-backed durable
 * outbox for guest lifecycle events that must survive plugin restarts.
 */
class GatewayClient(
    private val config: GatewayConfig,
    private val listener: GatewayListener,
    private val scheduler: DelayScheduler,
    private val stats: ServerStatsProvider,
    /** the ownership probe answer: is this uuid in the game right now? */
    private val presenceLookup: (uuid: String) -> Boolean,
    /** inbound fan-out to the ws layer, optional so presence-only setups stay light */
    private val rawListener: RawFrameListener? = null,
    /** durable park for DURABLE frames, file-backed in production */
    private val durablePark: DurableOutbox,
    /** injectable clock so the state machine is testable */
    private val now: () -> Long = { System.currentTimeMillis() },
) : WebSocket.Listener {
    private val codec = EnvelopeCodec(config.secret)
    private val httpClient: HttpClient = HttpClient.newHttpClient()
    private val reconnectPolicy = ReconnectPolicy(config.reconnectInitialMs, config.reconnectMaxMs)

    private val webSocketRef = AtomicReference<WebSocket?>(null)
    private val connected = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)
    private val reconnectAttempt = AtomicLong(0)
    private val lastInboundAt = AtomicLong(0)

    /** frames sent but not yet acked, keyed by envelope id */
    private val inFlight = ConcurrentHashMap<String, InFlight>()

    /** serializes every socket write so frame order is deterministic */
    private val sendLock = Any()

    /** presence events produced while offline, replayed in order on reconnect */
    private val pendingQueue = ArrayDeque<OutgoingFrame>()
    private val pendingLock = Any()

    /** remembers processed backend pushes so a redelivery is acked, not re-run */
    private val dedupe = InboundDedupe(config.dedupeTtlMs)

    private val partialBuffer = StringBuilder()

    fun start() {
        shuttingDown.set(false)
        lastInboundAt.set(now())
        runWatchdog()
        connect()
    }

    fun stop() {
        shuttingDown.set(true)
        webSocketRef.getAndSet(null)?.abort()
        connected.set(false)
        durablePark.flushNow()
    }

    fun isConnected(): Boolean = connected.get()

    /** test seam: pretend a socket exists without going through the transport */
    internal fun attachForTest(socket: WebSocket) {
        webSocketRef.set(socket)
    }

    /** fire-and-queue: never blocks the caller, never throws, never loses durable frames */
    fun send(type: MessageType, payload: Payload) {
        send(type, payload, durabilityOf(type))
    }

    fun send(type: MessageType, payload: Payload, durability: Durability) {
        val envelope = Envelope(codec.newMessageId(), type, now(), payload)
        dispatch(
            OutgoingFrame(
                envelope.id, type, codec.encode(envelope),
                key = idempotencyKey(type, codec.payloadAsJson(payload) as? JsonObject, envelope.id),
                durability = durability, enqueuedAt = now(),
            ),
        )
    }

    /**
     * ws layer entry point: relay a raw json payload under a known wire type.
     * the caller's envelope id is kept so redeliveries carry the same identity
     * and the backend can dedupe by id. returns false only for unknown types.
     */
    fun sendRaw(id: String, type: String, payload: JsonObject, correlationId: String?): Boolean {
        val messageType = MessageType.fromWire(type) ?: return false
        val envelope = Envelope(id, messageType, now(), Payload.Raw(payload), correlationId)
        dispatch(
            OutgoingFrame(
                id, messageType, codec.encode(envelope),
                key = idempotencyKey(messageType, payload, id),
                durability = durabilityOf(messageType), enqueuedAt = now(),
            ),
        )
        return true
    }

    private fun dispatch(frame: OutgoingFrame) {
        val socket = webSocketRef.get()
        if (connected.get() && socket != null) {
            sendTracked(socket, frame)
        } else {
            park(frame, "link down")
        }
    }

    /**
     * sends and remembers the frame until its ack arrives (or it dies). a
     * failing send future means the connection died mid-flight: the frame goes
     * straight back to the park, the reconnect machinery takes over.
     */
    private fun sendTracked(socket: WebSocket, frame: OutgoingFrame) {
        synchronized(sendLock) {
            try {
                val future = socket.sendText(frame.wire, true)
                inFlight[frame.id] = InFlight(frame, attempts = 0, deadline = now() + config.ackTimeoutMs)
                future.whenComplete { _, error ->
                    if (error != null) {
                        inFlight.remove(frame.id)
                        park(frame, "send failed mid-flight")
                        handleDisconnect(socket, "send failed: ${error.message}")
                    }
                }
            } catch (e: Exception) {
                park(frame, "send rejected")
                handleDisconnect(socket, "send failed: ${e.message}")
            }
        }
    }

    private fun park(frame: OutgoingFrame, reason: String) {
        when (frame.durability) {
            Durability.DURABLE -> durablePark.park(
                ParkedFrame(id = frame.id, key = frame.key, type = frame.type.wire, wire = frame.wire, enqueuedAt = frame.enqueuedAt),
            )
            Durability.QUEUE -> synchronized(pendingLock) {
                if (pendingQueue.size >= MAX_PENDING) {
                    // bounded: the oldest events are the least useful, drop them
                    pendingQueue.poll()
                }
                pendingQueue.add(frame)
            }
            Durability.DROP -> Unit
        }
    }

    /** reconnect replay: everything parked goes out chronologically */
    private fun drainParked(socket: WebSocket) {
        val ephemeral = synchronized(pendingLock) {
            val copy = ArrayList(pendingQueue)
            pendingQueue.clear()
            copy
        }
        val durable = durablePark.snapshot().map { parked ->
            OutgoingFrame(
                id = parked.id,
                type = MessageType.fromWire(parked.type) ?: return,
                wire = parked.wire,
                key = parked.key,
                durability = Durability.DURABLE,
                enqueuedAt = parked.enqueuedAt,
            )
        }
        for (frame in (ephemeral + durable).sortedBy { it.enqueuedAt }) {
            if (!connected.get()) {
                // link died mid-drain, whatever is left stays parked
                if (frame.durability != Durability.DROP) park(frame, "link died during drain")
                return
            }
            sendTracked(socket, frame)
        }
    }

    private fun connect() {
        if (shuttingDown.get()) return
        try {
            val socket = httpClient.newWebSocketBuilder()
                .buildAsync(buildHandshakeUri(), this)
                .join()
            webSocketRef.set(socket)
        } catch (e: Exception) {
            scheduleReconnect("connect failed: ${e.message}")
        }
    }

    /** backend verifies server|ts|nonce pre-upgrade; the nonce is single-use */
    private fun buildHandshakeUri(): URI {
        val base = config.url.toURIOrNull() ?: throw IllegalStateException("invalid backend url: ${config.url}")
        val ts = now().toString()
        val nonce = codec.newMessageId()
        val signature = HandshakeSigner.signature(config.secret, config.serverId, ts, nonce)
        val query = "server=${urlEncode(config.serverId)}&ts=$ts&nonce=$nonce&sig=$signature"
        val path = base.rawPath.ifEmpty { "/" }
        return URI(base.scheme, base.rawAuthority, path, query, null)
    }

    private fun scheduleReconnect(reason: String) {
        if (shuttingDown.get()) return
        val attempt = reconnectAttempt.incrementAndGet()
        val delay = reconnectPolicy.delayFor(attempt)
        listener.onDisconnected()
        scheduler.scheduleDelayed({ connect() }, delay)
    }

    /** only the first disconnector for a given socket schedules the reconnect */
    private fun handleDisconnect(socket: WebSocket, reason: String) {
        if (!webSocketRef.compareAndSet(socket, null)) return
        connected.set(false)
        // whatever was mid-flight goes back to the park, chronological order
        val stranded = inFlight.values.map { it.frame }.sortedBy { it.enqueuedAt }
        inFlight.clear()
        stranded.forEach { park(it, "connection dropped mid-flight") }
        scheduleReconnect(reason)
    }

    /**
     * the periodic watchdog: retries ack-timeout frames a bounded number of
     * times, then parks them for good; and disconnects a socket that stopped
     * speaking to us (half-open detection). internal so tests can tick it
     * with an injected clock.
     */
    internal fun watchdogTick() {
        val t = now()
        for ((id, pending) in inFlight) {
            if (t < pending.deadline) continue
            if (!inFlight.remove(id, pending)) continue
            if (pending.attempts < config.ackMaxRetries) {
                val socket = webSocketRef.get()
                if (socket != null && connected.get()) {
                    pending.attempts += 1
                    pending.deadline = t + config.ackTimeoutMs
                    val retried = synchronized(sendLock) {
                        runCatching { socket.sendText(pending.frame.wire, true) }
                    }
                    if (retried.isSuccess) inFlight[id] = pending
                    else park(pending.frame, "retry send failed")
                } else {
                    park(pending.frame, "link gone before ack")
                }
            } else {
                // bounded retries are over: durable frames park forever,
                // the rest were best-effort anyway
                park(pending.frame, "ack timeout after ${pending.attempts + 1} attempts")
            }
        }
        if (connected.get()) {
            val socket = webSocketRef.get()
            if (socket != null && t - lastInboundAt.get() > config.livenessTimeoutMs) {
                handleDisconnect(socket, "no inbound traffic for ${config.livenessTimeoutMs}ms")
            }
        }
    }

    private fun runWatchdog() {
        scheduler.scheduleDelayed({
            if (shuttingDown.get()) return@scheduleDelayed
            watchdogTick()
            runWatchdog()
        }, WATCHDOG_TICK_MS)
    }

    // ---- WebSocket.Listener ----

    override fun onOpen(webSocket: WebSocket) {
        reconnectAttempt.set(0)
        connected.set(true)
        lastInboundAt.set(now())
        webSocket.request(1)

        // registration hello: identity + current load, backend acks it
        val hello = Envelope(
            codec.newMessageId(),
            MessageType.SERVER_STATUS,
            now(),
            Payload.ServerStatus(stats.onlinePlayers(), stats.maxPlayers(), stats.serverVersion()),
        )
        synchronized(sendLock) {
            try {
                webSocket.sendText(codec.encode(hello), true)
            } catch (e: Exception) {
                handleDisconnect(webSocket, "hello send failed: ${e.message}")
                return
            }
        }
        drainParked(webSocket)
        listener.onConnected()
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        synchronized(partialBuffer) {
            partialBuffer.append(data)
            if (last) {
                val raw = partialBuffer.toString()
                partialBuffer.setLength(0)
                handleFrame(webSocket, raw)
            }
        }
        webSocket.request(1)
        return null
    }

    private fun handleFrame(webSocket: WebSocket, raw: String) {
        val envelope = try {
            codec.decode(raw, now(), config.maxSkewMs, config.maxPacketBytes).envelope
        } catch (e: ProtocolException) {
            // protocol violations are fatal on both ends — drop and retry later
            handleDisconnect(webSocket, "protocol violation: ${e.message}")
            return
        } catch (e: Exception) {
            handleDisconnect(webSocket, "malformed frame: ${e.message}")
            return
        }
        lastInboundAt.set(now())

        when (envelope.type) {
            MessageType.ACK -> {
                val acked = (envelope.payload as Payload.Ack).ackedId
                inFlight.remove(acked)?.let { pending ->
                    if (pending.frame.durability == Durability.DURABLE) durablePark.unpark(acked)
                }
            }
            MessageType.ERROR -> Unit // transport-level, nothing to do
            MessageType.SESSION_CHECK -> answerSessionCheck(webSocket, envelope)
            else -> deliver(envelope)
        }

        // every backend push expects an ack unless it's transport-level; a
        // deduped redelivery is acked too, the sender needs its closure
        if (envelope.type != MessageType.ACK && envelope.type != MessageType.ERROR) {
            sendAck(webSocket, envelope.id, envelope.correlationId)
        }
    }

    /** typed callbacks plus the raw fan-out, deduped so a redelivery never re-runs */
    private fun deliver(envelope: Envelope) {
        if (envelope.type in DEDUPEABLE_TYPES) {
            val key = envelope.type.wire + "|" + codec.payloadAsJson(envelope.payload).toString()
            if (dedupe.isDuplicate(key, now())) {
                return
            }
        }
        when (envelope.type) {
            MessageType.LINK_TOKEN -> listener.onLinkToken(envelope.payload as Payload.LinkToken)
            MessageType.ACCOUNT_DELETED -> listener.onAccountDeleted(envelope.payload as Payload.AccountDeleted)
            else -> Unit
        }
        rawListener?.onFrame(
            envelope.id,
            envelope.type.wire,
            codec.payloadAsJson(envelope.payload) as? JsonObject ?: JsonObject(emptyMap()),
            envelope.correlationId,
        )
    }

    private fun sendAck(webSocket: WebSocket, id: String, correlationId: String?) {
        val ack = Envelope(
            codec.newMessageId(),
            MessageType.ACK,
            now(),
            Payload.Ack(id),
            correlationId,
        )
        synchronized(sendLock) {
            try {
                webSocket.sendText(codec.encode(ack), true)
            } catch (e: Exception) {
                handleDisconnect(webSocket, "ack send failed: ${e.message}")
            }
        }
    }

    private fun answerSessionCheck(webSocket: WebSocket, envelope: Envelope) {
        val check = envelope.payload as Payload.SessionCheck
        val online = presenceLookup(check.minecraftUuid)
        val reply = Envelope(
            codec.newMessageId(),
            MessageType.SESSION_CHECK_RESULT,
            now(),
            Payload.SessionCheckResult(check.minecraftUuid, online),
            envelope.correlationId,
        )
        synchronized(sendLock) {
            try {
                webSocket.sendText(codec.encode(reply), true)
            } catch (e: Exception) {
                handleDisconnect(webSocket, "probe reply failed: ${e.message}")
            }
        }
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        handleDisconnect(webSocket, "closed ($statusCode) $reason")
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        handleDisconnect(webSocket, "error: ${error.message}")
    }

    private companion object {
        const val MAX_PENDING = 1_000
        const val WATCHDOG_TICK_MS = 1_000L

        /** backend pushes a redelivery of these must never re-trigger */
        val DEDUPEABLE_TYPES = setOf(
            MessageType.AUTH_REQUEST,
            MessageType.AUTH_APPROVED,
            MessageType.AUTH_REJECTED,
            MessageType.AUTH_EXPIRED,
            MessageType.LINK_TOKEN,
            MessageType.ACCOUNT_DELETED,
        )

        /** guest events dedupe on sessionId+type, everything else on the envelope id */
        fun idempotencyKey(type: MessageType, payload: JsonObject?, fallbackId: String): String {
            val sessionId = payload?.get("sessionId")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            return if (sessionId != null && (type == MessageType.GUEST_STARTED || type == MessageType.GUEST_ENDED)) {
                "${type.wire}|$sessionId"
            } else {
                "${type.wire}|$fallbackId"
            }
        }
    }
}

/** the byte-exact pre-upgrade signature: hmac-sha256(secret, "server|ts|nonce") */
object HandshakeSigner {
    fun signature(secret: String, serverId: String, ts: String, nonce: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val payload = "$serverId|$ts|$nonce"
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

private fun String.toURIOrNull(): URI? = try {
    URI(this)
} catch (e: Exception) {
    null
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
