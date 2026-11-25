package kz.bejiihiu.servercore.websocket

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
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

    /** live count of active spec sessions from the plugin's own registry */
    fun activeSpecSessions(): Int = 0
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

    /**
     * every decoded inbound frame as raw json, fired before the typed
     * callbacks. covers the types this build has no typed payload for (auth
     * verdicts, guest lifecycle, application events, locale pushes, unknown
     * future types) so consumers speaking the documented wire names get them
     * without the transport layer knowing each one. ack/error frames and
     * deduped redeliveries never reach it. default empty, existing listeners
     * keep compiling.
     */
    fun onWireMessage(wireType: String, payload: JsonObject, correlationId: String?, timestamp: Long) {}
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
    /** correlated backend failures this frame already survived */
    val errorAttempts: Int = 0,
)

private class InFlight(
    val frame: OutgoingFrame,
    var attempts: Int,
    var deadline: Long,
)

/** a frame the backend failed to process, waiting for the retry tick */
private class ErrorRetry(val frame: OutgoingFrame, val dueAt: Long)

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
 * dedupe for redelivered backend pushes, a bounded in-memory queue for
 * replayable events emitted while the connection was down and a disk-backed
 * durable outbox for guest lifecycle events that must survive plugin restarts.
 */
class GatewayClient(
    private val config: GatewayConfig,
    private val listener: GatewayListener,
    private val scheduler: DelayScheduler,
    private val stats: ServerStatsProvider,
    /** the ownership probe answer: is this uuid in the game right now? */
    private val presenceLookup: (uuid: String) -> Boolean,
    /** durable park for DURABLE frames, file-backed in production */
    private val durablePark: DurableOutbox,
    /** injectable clock so the state machine is testable */
    private val now: () -> Long = { System.currentTimeMillis() },
    /** warnings about frames the backend rejected or abandoned; the plugin wires it to its logger */
    private val log: (String) -> Unit = {},
) : WebSocket.Listener {
    private val codec = EnvelopeCodec(config.secret)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        // a hung tcp connect must die on its own, otherwise join() below waits
        // forever and the reconnect loop stalls with no watchdog rescue (the
        // liveness check only runs while connected=true)
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val reconnectPolicy = ReconnectPolicy(config.reconnectInitialMs, config.reconnectMaxMs)

    private val webSocketRef = AtomicReference<WebSocket?>(null)
    private val connected = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)
    private val reconnectAttempt = AtomicLong(0)
    private val lastInboundAt = AtomicLong(0)

    /** frames sent but not yet acked, keyed by envelope id */
    private val inFlight = ConcurrentHashMap<String, InFlight>()

    /**
     * frames the backend acked but may still answer with a correlated ERROR.
     * an ack means "received", not "processed", and the failure answer follows
     * on the same connection, so a retired frame has to stay addressable for a
     * short window. only replayable classes are kept: a heartbeat storm must
     * never evict the frames that actually need a retry.
     */
    private val recentlyAcked = LinkedHashMap<String, OutgoingFrame>()
    private val recentlyAckedLock = Any()

    /**
     * at least the outbox cap: a reconnect replays up to that many frames in a
     * burst, and a handler failure behind the burst must still find its frame
     */
    private val recentlyAckedLimit = maxOf(config.outboxMaxEntries, MIN_RECENTLY_ACKED)

    /** failed frames waiting for the retry tick, keyed by their fresh envelope id */
    private val errorRetries = ConcurrentHashMap<String, ErrorRetry>()

    /** frames given up on after a correlated failure */
    private val droppedAfterError = AtomicLong()

    /** serializes every socket write so frame order is deterministic */
    private val sendLock = Any()

    /** replayable events produced while offline, replayed in order on reconnect */
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
        val socket = webSocketRef.getAndSet(null)
        if (socket != null) {
            // whatever was sent but not acked yet goes back to the park, the
            // abort below discards the socket's pending writes otherwise
            val stranded = inFlight.values.map { it.frame }.sortedBy { it.enqueuedAt }
            inFlight.clear()
            stranded.filter { it.durability == Durability.DURABLE }.forEach { park(it, "shutdown before ack") }
            // graceful close first: sendClose is queued behind the pending
            // sends, so everything already written gets flushed before the
            // close frame goes out. abort is the bounded fallback for a
            // socket that never finishes flushing
            val closed = runCatching { socket.sendClose(1000, null) }.isSuccess
            if (closed) {
                java.util.concurrent.CompletableFuture.delayedExecutor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .execute { socket.abort() }
            } else {
                socket.abort()
            }
        }
        connected.set(false)
        // error retries still waiting for their tick go back to the park, not
        // into the void: durable copies are already there, queued ones are not
        val pendingRetries = errorRetries.values.map { it.frame }
        errorRetries.clear()
        pendingRetries.forEach { park(it, "shutdown before error retry") }
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
        val envelope = Envelope(id, messageType, now(), Payload.Raw(type, payload), correlationId)
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
                ParkedFrame(
                    id = frame.id,
                    key = frame.key,
                    type = frame.type.wire,
                    wire = frame.wire,
                    enqueuedAt = frame.enqueuedAt,
                    errorAttempts = frame.errorAttempts,
                ),
            )
            Durability.QUEUE -> synchronized(pendingLock) {
                // the same frame can be parked twice (the send-failure callback
                // and the disconnect sweep both see it); replaying both copies
                // re-sends a frame the peer already got. one copy per envelope id
                if (pendingQueue.none { it.id == frame.id }) {
                    if (pendingQueue.size >= MAX_PENDING) {
                        // bounded: the oldest events are the least useful, drop them
                        pendingQueue.poll()
                    }
                    pendingQueue.add(frame)
                }
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
        val durable = durablePark.snapshot().mapNotNull { parked ->
            val type = MessageType.fromWire(parked.type) ?: return@mapNotNull null
            OutgoingFrame(
                id = parked.id,
                type = type,
                wire = parked.wire,
                key = parked.key,
                durability = Durability.DURABLE,
                enqueuedAt = parked.enqueuedAt,
                errorAttempts = parked.errorAttempts,
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
                // same story for the ws handshake itself: bound it or a dead
                // backend can freeze a scheduler thread indefinitely
                .connectTimeout(java.time.Duration.ofSeconds(10))
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
            // the entry stays in the map for the retry itself: removing and
            // re-adding it would open a window where an ack landing in between
            // finds nothing to remove and the frame turns into an immortal
            // zombie that re-parks forever. the ack thread wins the race by
            // removing the entry first; we only take it out when parking.
            if (pending.attempts < config.ackMaxRetries) {
                val socket = webSocketRef.get()
                if (socket != null && connected.get()) {
                    pending.attempts += 1
                    pending.deadline = t + config.ackTimeoutMs
                    val retried = synchronized(sendLock) {
                        runCatching { socket.sendText(pending.frame.wire, true) }
                    }
                    if (!retried.isSuccess && inFlight.remove(id, pending)) {
                        park(pending.frame, "retry send failed")
                    }
                } else if (inFlight.remove(id, pending)) {
                    park(pending.frame, "link gone before ack")
                }
            } else if (inFlight.remove(id, pending)) {
                // bounded retries are over: durable frames park forever,
                // the rest were best-effort anyway
                park(pending.frame, "ack timeout after ${pending.attempts + 1} attempts")
            }
        }
        // correlated failures: resend what the backend reported as not applied
        for ((id, retry) in errorRetries) {
            if (t < retry.dueAt) continue
            if (!errorRetries.remove(id, retry)) continue
            val socket = webSocketRef.get()
            if (socket == null || !connected.get()) {
                // durable copies are already parked; a queued frame still needs
                // its park so the next link replays it
                park(retry.frame, "link gone before error retry")
                continue
            }
            sendTracked(socket, retry.frame)
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
            Payload.ServerStatus(stats.onlinePlayers(), stats.maxPlayers(), stats.serverVersion(), stats.activeSpecSessions()),
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
        val decoded = try {
            codec.decode(raw, now(), config.maxSkewMs, config.maxPacketBytes)
        } catch (e: ProtocolException) {
            // protocol violations are fatal on both ends — drop and retry later
            handleDisconnect(webSocket, "protocol violation: ${e.message}")
            return
        } catch (e: Exception) {
            handleDisconnect(webSocket, "malformed frame: ${e.message}")
            return
        }
        val envelope = decoded.envelope
        lastInboundAt.set(now())

        when (envelope.type) {
            MessageType.ACK -> {
                val acked = (envelope.payload as Payload.Ack).ackedId
                val retired = inFlight.remove(acked)
                // retire the parked copy even when the in-flight entry is
                // already gone (a concurrent retry tick removed it first) -
                // otherwise an acked durable frame parks forever and replays
                // on every reconnect
                durablePark.unpark(acked)
                // the ack is only "received": remember the frame in case the
                // handler fails and the backend answers with a correlated ERROR
                if (retired != null) rememberAcked(retired.frame)
            }
            MessageType.ERROR -> handleError(envelope.payload as Payload.Error)
            MessageType.SESSION_CHECK -> answerSessionCheck(webSocket, envelope)
            else -> deliver(decoded)
        }

        // every backend push expects an ack unless it's transport-level; a
        // deduped redelivery is acked too, the sender needs its closure
        if (envelope.type != MessageType.ACK && envelope.type != MessageType.ERROR) {
            sendAck(webSocket, envelope.id, envelope.correlationId)
        }
    }

    /** typed callbacks plus the raw fan-out, deduped so a redelivery never re-runs */
    private fun deliver(decoded: DecodedMessage) {
        val envelope = decoded.envelope
        if (envelope.type in DEDUPEABLE_TYPES) {
            val key = envelope.type.wire + "|" + decoded.rawPayload.toString()
            if (dedupe.isDuplicate(key, now())) {
                return
            }
        }
        when (envelope.type) {
            MessageType.LINK_TOKEN -> listener.onLinkToken(envelope.payload as Payload.LinkToken)
            MessageType.ACCOUNT_DELETED -> listener.onAccountDeleted(envelope.payload as Payload.AccountDeleted)
            else -> Unit
        }
        // raw fan-out: consumers speaking the documented wire names (the ws
        // layer's guest/auth handling, locale pushes, unknown future types)
        // get the plain json whatever the typed layer knows
        val wireName = (envelope.payload as? Payload.Raw)?.wireType ?: envelope.type.wire
        listener.onWireMessage(wireName, decoded.rawPayload, envelope.correlationId, envelope.timestamp)
    }

    private fun rememberAcked(frame: OutgoingFrame) {
        if (frame.durability == Durability.DROP) return
        synchronized(recentlyAckedLock) {
            recentlyAcked.remove(frame.id)
            recentlyAcked[frame.id] = frame
            while (recentlyAcked.size > recentlyAckedLimit) {
                recentlyAcked.remove(recentlyAcked.keys.first())
            }
        }
    }

    private fun takeAcked(id: String): OutgoingFrame? =
        synchronized(recentlyAckedLock) { recentlyAcked.remove(id) }

    /**
     * a correlated failure: the backend acked the frame and then failed to
     * process it (database blip mid-handler). the ack already retired the
     * frame, so put it back and let the retry tick send it again under a fresh
     * envelope id — the backend would only re-ack a recycled id without ever
     * running the handler again.
     */
    private fun handleError(error: Payload.Error) {
        val inReplyTo = error.inReplyTo
        if (inReplyTo == null) {
            log("backend reported ${error.code}: ${error.message}")
            return
        }
        // rarely the failure can land before the ack: take the frame from
        // whichever side still holds it
        val frame = takeAcked(inReplyTo) ?: inFlight.remove(inReplyTo)?.frame
        if (frame == null) {
            log("backend reported ${error.code} for frame $inReplyTo, which is not pending: ignored")
            return
        }
        when {
            // deterministic: the backend will never accept this payload, so a
            // retry only burns attempts and outbox slots
            error.code in NON_RETRYABLE_CODES ->
                giveUp(frame, "${error.code}: ${error.message}, not retried")
            frame.durability == Durability.DROP ->
                log("backend failed ${frame.type.wire}: ${error.code}: ${error.message}")
            frame.errorAttempts >= MAX_ERROR_RETRIES ->
                giveUp(frame, "${error.code}: ${error.message} after ${frame.errorAttempts} retries")
            else -> requeue(frame, error)
        }
    }

    private fun requeue(frame: OutgoingFrame, error: Payload.Error) {
        val attempts = frame.errorAttempts + 1
        val retried = reissue(frame, attempts)
        if (retried == null) {
            giveUp(frame, "${error.code}: ${error.message}, the frame could not be re-signed")
            return
        }
        // park before the retry actually goes out: a link that dies in between
        // must not swallow the frame. the ack of the retried copy unparks it.
        if (retried.durability == Durability.DURABLE) park(retried, "backend failed ${error.code}")
        errorRetries[retried.id] = ErrorRetry(retried, now() + ERROR_RETRY_DELAY_MS)
        log(
            "backend failed ${frame.type.wire} (${error.code}: ${error.message}), " +
                "retry $attempts/$MAX_ERROR_RETRIES scheduled",
        )
    }

    /** same frame, fresh id and timestamp: a retry has to be a new frame to the backend */
    private fun reissue(frame: OutgoingFrame, errorAttempts: Int): OutgoingFrame? {
        val id = codec.newMessageId()
        val wire = runCatching { codec.reissue(frame.wire, id, now()) }.getOrNull() ?: return null
        return OutgoingFrame(id, frame.type, wire, frame.key, frame.durability, frame.enqueuedAt, errorAttempts)
    }

    private fun giveUp(frame: OutgoingFrame, reason: String) {
        droppedAfterError.incrementAndGet()
        durablePark.unpark(frame.id)
        log("dropping ${frame.type.wire} frame ${frame.id}: $reason")
    }

    /** frames abandoned after a correlated backend failure (rejected payloads included) */
    internal fun droppedAfterBackendError(): Long = droppedAfterError.get()

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

        /** floor of the recently-acked window, the real cap follows the outbox size */
        const val MIN_RECENTLY_ACKED = 128

        /** resends after correlated backend failures, then the frame is dropped */
        const val MAX_ERROR_RETRIES = 3

        /** how long a failed frame waits before the retry tick resends it */
        const val ERROR_RETRY_DELAY_MS = 1_000L

        /** failures the backend repeats no matter how often we resend */
        val NON_RETRYABLE_CODES = setOf("BAD_PAYLOAD")

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
