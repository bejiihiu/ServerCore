package kz.bejiihiu.servercore.ws

import java.util.concurrent.CopyOnWriteArrayList

/**
 * outbound view of the backend connection. the websocket layer attaches a real
 * link here; until then the gateway reports disconnected and every send is
 * dropped, which keeps guest mode fully functional locally (fail closed).
 *
 * implementations of [BackendLink] must never block the calling thread —
 * sends are fire-and-forget from region threads.
 */
interface BackendLink {
    /** true only while the connection is live and the handshake acknowledged */
    val connected: Boolean

    /** enqueue a message, false when the link is down or the frame was rejected */
    fun send(envelope: WsEnvelope): Boolean
}

/** inbound + lifecycle callbacks fired by the gateway */
interface GatewayListener {
    fun onMessage(envelope: WsEnvelope)

    /** called on every attach of a connected link and once for late listeners */
    fun onConnected() {}

    fun onDisconnected() {}
}

/**
 * hub between the plugin modules and the websocket client. the ws layer calls
 * [attach] when its connection becomes usable, [receive] for every validated
 * inbound frame, and [detach] when it goes down.
 */
class BackendGateway : BackendLink {

    @Volatile
    private var link: BackendLink? = null

    private val listeners = CopyOnWriteArrayList<GatewayListener>()

    override val connected: Boolean
        get() = link?.connected == true

    override fun send(envelope: WsEnvelope): Boolean = link?.send(envelope) ?: false

    /** called by the ws layer for every inbound frame, fanned out to listeners */
    fun receive(envelope: WsEnvelope) {
        for (listener in listeners) {
            // one broken listener must not starve the others
            runCatching { listener.onMessage(envelope) }
                .onFailure { System.err.println("servercore: gateway listener failed on ${envelope.type}: $it") }
        }
    }

    fun attach(link: BackendLink) {
        this.link = link
        listeners.forEach { it.onConnected() }
    }

    fun detach(link: BackendLink) {
        if (this.link === link) {
            this.link = null
            listeners.forEach { it.onDisconnected() }
        }
    }

    fun addListener(listener: GatewayListener) {
        listeners.add(listener)
        if (connected) listener.onConnected()
    }

    fun removeListener(listener: GatewayListener) {
        listeners.remove(listener)
    }
}
