package kz.bejiihiu.servercore.bars

import java.util.concurrent.atomic.AtomicReference
import kz.bejiihiu.servercore.ws.GatewayListener
import kz.bejiihiu.servercore.ws.MessageTypes
import kz.bejiihiu.servercore.ws.NetworkSnapshotPayload
import kz.bejiihiu.servercore.ws.WsEnvelope
import kz.bejiihiu.servercore.ws.decodePayload

/**
 * one server as the backend last described it.
 *
 * [lastSeenAgeMs] is the age of that server's last heartbeat at the moment the
 * aggregate was built, not at the moment the bar reads it. the difference is
 * added locally ([NetworkStateCache] stamps the receipt), so a link that dies
 * keeps aging instead of freezing at a healthy-looking zero.
 */
data class NetServerView(
    val serverId: String,
    val onlinePlayers: Int,
    /** heartbeat age when the snapshot was generated, milliseconds */
    val lastSeenAgeMs: Long,
)

/**
 * the network as the backend last described it, plus the local clock that
 * received it. immutable: the cache swaps whole snapshots, so a region thread
 * never reads a half-updated list.
 */
data class NetSnapshot(
    /** players across every known server, as the backend summed them */
    val playersOnline: Int,
    val servers: List<NetServerView>,
    /** local epoch ms the frame arrived at */
    val receivedAt: Long,
)

/**
 * this server's own view of itself, used when no aggregate has arrived yet
 * (backend down, or an older backend that never sends one). the bar says
 * "1/1" instead of a dash: this server still is a network of one.
 */
data class LocalNetView(val onlinePlayers: Int)

/** read seam the metric goes through; a plain map in tests, the cache in game */
interface NetSnapshotSource {
    /** null = nothing received since boot (or the link never came up) */
    fun latest(): NetSnapshot?

    fun now(): Long
}

/**
 * holds the last NETWORK_SNAPSHOT. fed from the websocket thread, read from
 * every bar task — the only shared state is one atomic reference, so there is
 * no lock on the render path and no bukkit object anywhere near it.
 */
class NetworkStateCache(
    private val clock: () -> Long = System::currentTimeMillis,
) : NetSnapshotSource {

    private val current = AtomicReference<NetSnapshot?>(null)

    override fun latest(): NetSnapshot? = current.get()

    override fun now(): Long = clock()

    /** a fresh aggregate; replaces the previous one wholesale */
    fun accept(payload: NetworkSnapshotPayload) {
        current.set(
            NetSnapshot(
                playersOnline = payload.playersOnline.coerceAtLeast(0),
                servers = payload.servers.map { server ->
                    NetServerView(
                        serverId = server.serverId,
                        onlinePlayers = server.onlinePlayers.coerceAtLeast(0),
                        lastSeenAgeMs = server.lastSeenAgeMs.coerceAtLeast(0),
                    )
                },
                receivedAt = clock(),
            ),
        )
    }
}

/**
 * pumps NETWORK_SNAPSHOT frames into the cache. sits on the backend gateway's
 * listener fan-out, so it runs on the websocket thread and only touches the
 * atomic cache — no scheduler, no bukkit.
 *
 * a malformed frame is logged and dropped: one bad push must not cost the bar
 * its previous, still useful, snapshot.
 */
class NetSnapshotListener(private val cache: NetworkStateCache) : GatewayListener {

    override fun onMessage(envelope: WsEnvelope) {
        if (envelope.type != MessageTypes.NETWORK_SNAPSHOT) return
        val payload = runCatching { envelope.decodePayload<NetworkSnapshotPayload>() }
            .onFailure { System.err.println("servercore: bad NETWORK_SNAPSHOT payload, dropped ($it)") }
            .getOrNull() ?: return
        cache.accept(payload)
    }
}
