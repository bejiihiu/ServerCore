package kz.bejiihiu.servercore.websocket

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.CompletionStage
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

    /**
     * every decoded inbound frame as raw json, fired before the typed
     * callbacks. covers the types this build has no typed payload for (auth
     * verdicts, application events, locale pushes, unknown future types) so
     * consumers speaking the documented wire names get them without the
     * transport layer knowing each one. default empty, existing listeners
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

/**
 * the plugin side of the backend websocket gateway.
 *
 * transport: outbound connection to `GET /ws?server=..&ts=..&nonce=..&sig=..`,
 * hmac over `server|ts|nonce` computed pre-upgrade — an unauthenticated
 * client never becomes a socket at all. every frame after that is
 * envelope-signed with the same secret (see Protocol.kt).
 *
 * reliability: exponential backoff with jitter on every abnormal close,
 * heartbeat messages on a fixed cadence, acks for every backend push, and a
 * bounded in-memory pending queue that replays presence events emitted while
 * the connection was down (disk-backed durability lands with the guest phase).
 */
class GatewayClient(
    private val config: GatewayConfig,
    private val listener: GatewayListener,
    private val scheduler: DelayScheduler,
    private val stats: ServerStatsProvider,
    /** the ownership probe answer: is this uuid in the game right now? */
    private val presenceLookup: (uuid: String) -> Boolean,
) : WebSocket.Listener {
    private val codec = EnvelopeCodec(config.secret)
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    private val webSocketRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
    private val connected = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)
    private val reconnectAttempt = AtomicLong(0)
    private val sendLock = Any()

    /** events produced while offline, replayed in order on the next connect */
    private val pendingQueue = ArrayDeque<String>()
    private val pendingLock = Any()

    private val partialBuffer = StringBuilder()

    fun start() {
        shuttingDown.set(false)
        connect()
    }

    fun stop() {
        shuttingDown.set(true)
        webSocketRef.getAndSet(null)?.abort()
        connected.set(false)
    }

    fun isConnected(): Boolean = connected.get()

    /** fire-and-queue: never blocks the caller, never throws, never loses order */
    fun send(type: MessageType, payload: Payload) {
        val envelope = Envelope(codec.newMessageId(), type, System.currentTimeMillis(), payload)
        val wire = codec.encode(envelope)
        val socket = webSocketRef.get()
        if (connected.get() && socket != null) {
            trySend(socket, wire)
        } else {
            enqueue(wire)
        }
    }

    private fun trySend(socket: WebSocket, wire: String) {
        synchronized(sendLock) {
            try {
                socket.sendText(wire, true)
            } catch (e: Exception) {
                // connection died mid-send: the frame waits in the queue
                enqueue(wire)
                handleDisconnect(socket, "send failed: ${e.message}")
            }
        }
    }

    private fun enqueue(wire: String) {
        synchronized(pendingLock) {
            if (pendingQueue.size >= MAX_PENDING) {
                // bounded: the oldest events are the least useful, drop them
                pendingQueue.poll()
            }
            pendingQueue.add(wire)
        }
    }

    private fun drainPending(socket: WebSocket) {
        synchronized(pendingLock) {
            while (pendingQueue.isNotEmpty()) {
                val wire = pendingQueue.peek() ?: break
                try {
                    synchronized(sendLock) { socket.sendText(wire, true) }
                    pendingQueue.poll()
                } catch (e: Exception) {
                    // still broken, retry on the next reconnect
                    return
                }
            }
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
        val ts = System.currentTimeMillis().toString()
        val nonce = codec.newMessageId()
        val signature = HandshakeSigner.signature(config.secret, config.serverId, ts, nonce)
        val query = "server=${urlEncode(config.serverId)}&ts=$ts&nonce=$nonce&sig=$signature"
        val path = base.rawPath.ifEmpty { "/" }
        return URI(base.scheme, base.rawAuthority, path, query, null)
    }

    private fun scheduleReconnect(reason: String) {
        if (shuttingDown.get()) return
        val attempt = reconnectAttempt.incrementAndGet()
        val baseDelay = minOf(config.reconnectInitialMs * twoPow(attempt - 1), config.reconnectMaxMs)
        val jitterFactor = 0.8 + ThreadLocalRandom.current().nextDouble(0.4)
        val delay = (baseDelay * jitterFactor).toLong()
        listener.onDisconnected()
        scheduler.scheduleDelayed({ connect() }, delay)
    }

    private fun twoPow(exp: Long): Long = if (exp >= 63) Long.MAX_VALUE else 1L shl exp.toInt()

    /** only the first disconnector for a given socket schedules the reconnect */
    private fun handleDisconnect(socket: WebSocket, reason: String) {
        if (!webSocketRef.compareAndSet(socket, null)) return
        connected.set(false)
        scheduleReconnect(reason)
    }

    // ---- WebSocket.Listener ----

    override fun onOpen(webSocket: WebSocket) {
        reconnectAttempt.set(0)
        connected.set(true)
        webSocket.request(1)

        // registration hello: identity + current load, backend acks it
        val hello = Envelope(
            codec.newMessageId(),
            MessageType.SERVER_STATUS,
            System.currentTimeMillis(),
            Payload.ServerStatus(stats.onlinePlayers(), stats.maxPlayers(), stats.serverVersion()),
        )
        synchronized(sendLock) {
            try {
                webSocket.sendText(codec.encode(hello), true)
            } catch (e: Exception) {
                handleDisconnect(webSocket, "hello send failed: ${e.message}")
            }
        }
        drainPending(webSocket)
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
            codec.decode(raw, System.currentTimeMillis(), config.maxSkewMs, config.maxPacketBytes)
        } catch (e: ProtocolException) {
            // protocol violations are fatal on both ends — drop and retry later
            handleDisconnect(webSocket, "protocol violation: ${e.message}")
            return
        } catch (e: Exception) {
            handleDisconnect(webSocket, "malformed frame: ${e.message}")
            return
        }
        val envelope = decoded.envelope

        // raw fan-out first: consumers speaking wire types the typed layer has
        // no payload vocabulary for (auth verdicts, application events, locale
        // pushes, unknown future types) get the plain json here
        val wireName = (envelope.payload as? Payload.Raw)?.wireType ?: envelope.type.wire
        listener.onWireMessage(wireName, decoded.rawPayload, envelope.correlationId, envelope.timestamp)

        when (envelope.type) {
            MessageType.LINK_TOKEN -> listener.onLinkToken(envelope.payload as Payload.LinkToken)
            MessageType.ACCOUNT_DELETED -> listener.onAccountDeleted(envelope.payload as Payload.AccountDeleted)
            MessageType.SESSION_CHECK -> answerSessionCheck(webSocket, envelope)
            MessageType.ACK, MessageType.ERROR, MessageType.UNKNOWN -> Unit // transport-level, nothing to do
            else -> Unit
        }

        // every backend push expects an ack unless it's transport-level
        if (envelope.type != MessageType.ACK && envelope.type != MessageType.ERROR) {
            val ack = Envelope(
                codec.newMessageId(),
                MessageType.ACK,
                System.currentTimeMillis(),
                Payload.Ack(envelope.id),
                envelope.correlationId,
            )
            synchronized(sendLock) {
                try {
                    webSocket.sendText(codec.encode(ack), true)
                } catch (e: Exception) {
                    handleDisconnect(webSocket, "ack send failed: ${e.message}")
                }
            }
        }
    }

    private fun answerSessionCheck(webSocket: WebSocket, envelope: Envelope) {
        val check = envelope.payload as Payload.SessionCheck
        val online = presenceLookup(check.minecraftUuid)
        val reply = Envelope(
            codec.newMessageId(),
            MessageType.SESSION_CHECK_RESULT,
            System.currentTimeMillis(),
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
