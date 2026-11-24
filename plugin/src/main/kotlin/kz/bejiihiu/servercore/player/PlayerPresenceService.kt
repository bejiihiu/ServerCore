package kz.bejiihiu.servercore.player

import kz.bejiihiu.servercore.websocket.GatewayClient
import kz.bejiihiu.servercore.websocket.MessageType
import kz.bejiihiu.servercore.websocket.Payload
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * player presence tracking wired to the gateway: every join/quit becomes a
 * PLAYER_JOINED / PLAYER_LEFT frame.
 *
 * folia-correctness: join/quit fire on region threads, so the listeners only
 * touch their own ConcurrentHashMap (thread-safe by design) and hand the
 * websocket send to the gateway, which serializes socket writes from async
 * contexts. no bukkit entity is ever touched off-thread.
 */
class PlayerPresenceService(
    private val plugin: Plugin,
    private val gateway: GatewayClient,
    /** live count of active spec sessions, fed into every heartbeat */
    private val activeSpecSessions: () -> Int = { 0 },
) : Listener {

    /** online players seen by this server: uuid -> username */
    private val online = ConcurrentHashMap<UUID, String>()

    /** the ownership probe answer: is this uuid currently in the game here? */
    fun isOnline(minecraftUuid: String): Boolean {
        val uuid = runCatching { UUID.fromString(minecraftUuid) }.getOrNull() ?: return false
        return online.containsKey(uuid)
    }

    fun onlineCount(): Int = online.size

    /** periodic SERVER_HEARTBEAT on the async scheduler: no world access involved */
    fun startHeartbeat() {
        plugin.server.asyncScheduler.runAtFixedRate(
            plugin,
            { _ ->
                gateway.send(
                    MessageType.SERVER_HEARTBEAT,
                    Payload.ServerHeartbeat(onlineCount(), plugin.server.maxPlayers, activeSpecSessions()),
                )
            },
            1,
            15,
            TimeUnit.SECONDS,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        online[player.uniqueId] = player.name
        gateway.send(
            MessageType.PLAYER_JOINED,
            Payload.PlayerJoined(player.uniqueId.toString(), player.name),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        // username is read while the player object is still valid on this
        // region thread; only the string travels to the async side
        val username = player.name
        online.remove(player.uniqueId)
        gateway.send(
            MessageType.PLAYER_LEFT,
            Payload.PlayerLeft(player.uniqueId.toString()),
        )
    }
}
