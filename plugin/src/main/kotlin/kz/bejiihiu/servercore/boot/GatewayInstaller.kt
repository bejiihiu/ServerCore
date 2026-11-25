package kz.bejiihiu.servercore.boot

import java.util.UUID
import java.util.concurrent.TimeUnit
import kz.bejiihiu.servercore.player.PlayerPresenceService
import kz.bejiihiu.servercore.ui.UiFx
import kz.bejiihiu.servercore.websocket.DelayScheduler
import kz.bejiihiu.servercore.websocket.DurableOutbox
import kz.bejiihiu.servercore.websocket.GatewayClient
import kz.bejiihiu.servercore.websocket.GatewayListener
import kz.bejiihiu.servercore.websocket.Payload
import kz.bejiihiu.servercore.websocket.ServerStatsProvider
import kz.bejiihiu.servercore.ws.GatewayLink
import kz.bejiihiu.servercore.ws.OpsStatusSink
import kz.bejiihiu.servercore.ws.WsEnvelope
import kz.bejiihiu.servercore.ws.newMessageId
import kotlinx.serialization.json.JsonObject

/**
 * The websocket transport: durable outbox, gateway client with its reconnect
 * and watchdog machinery, the ops status sink and the presence tracker.
 *
 * The guest hub is attached lazily: it lives in the guest installer, which
 * comes up after this one, and the client only starts receiving frames once
 * [start] runs at the very end of the boot. Presence lookups follow the same
 * lazy shape, so the stats provider is honest while the client is constructed.
 */
class GatewayInstaller(
    private val ctx: PluginContext,
    private val ops: OpsInstaller,
) {

    private var gatewayClient: GatewayClient? = null
    private var presenceService: PlayerPresenceService? = null

    val client: GatewayClient
        get() = checkNotNull(gatewayClient) { "gateway client read before install" }

    fun install() {
        val plugin = ctx.plugin
        val pluginConfig = ctx.pluginConfig

        val stats = object : ServerStatsProvider {
            override fun onlinePlayers(): Int = presenceService?.onlineCount() ?: 0
            override fun maxPlayers(): Int = plugin.server.maxPlayers
            override fun serverVersion(): String = plugin.server.minecraftVersion
            override fun activeSpecSessions(): Int = ctx.activeSpecSessions()
        }

        // durable outbox: guest lifecycle frames survive reconnects AND plugin
        // restarts, parked on disk until the backend acknowledges them
        val durableOutbox = DurableOutbox(
            file = ctx.dataFolder.toPath().resolve("ws-outbox.json"),
            io = ctx.io,
            maxEntries = pluginConfig.gateway.outboxMaxEntries,
        )
        durableOutbox.load()

        // the link adapter is created after the client (it wraps it), so the
        // listener captures a mutable reference
        var gatewayLink: GatewayLink? = null
        val gatewayListener = object : GatewayListener {
            override fun onConnected() {
                ctx.logger.info("backend gateway connected as ${pluginConfig.gateway.serverId}")
                // the guest hub goes live with the transport, then both sides
                // re-sync what happened while the link was down
                gatewayLink?.let { ctx.backendGateway.attach(it) }
                ctx.guestSessions.reconcile()
            }

            override fun onDisconnected() {
                // the client already logged the failure with its reason
                gatewayLink?.let { ctx.backendGateway.detach(it) }
            }

            override fun onLinkToken(payload: Payload.LinkToken) {
                // per-join, and the backend logs the real linking event
                ctx.logger.fine("link token issued for ${payload.username}")
            }

            override fun onAccountDeleted(payload: Payload.AccountDeleted) {
                revokeDeletedPlayer(payload.minecraftUuid)
            }

            // raw fan-out bridge: the ws/* modules speak WsEnvelope, the typed
            // transport speaks Payload. every validated inbound frame travels
            // through here as json, whatever its type. only safe after the guest
            // hub is assigned, which is guaranteed because the client only
            // starts receiving frames once started at the end of the boot
            override fun onWireMessage(
                wireType: String,
                payload: JsonObject,
                correlationId: String?,
                timestamp: Long,
            ) {
                ctx.backendGateway.receive(
                    WsEnvelope(
                        id = newMessageId(),
                        type = wireType,
                        timestamp = timestamp,
                        correlationId = correlationId,
                        payload = payload,
                    ),
                )
            }
        }

        // folia async scheduler drives reconnect delays and the watchdog; the
        // client itself is bukkit-free, so its callbacks may run on any async thread
        val delayScheduler = DelayScheduler { task: Runnable, delayMs: Long ->
            plugin.server.asyncScheduler.runDelayed(plugin, { _ -> task.run() }, delayMs, TimeUnit.MILLISECONDS)
        }

        val client = GatewayClient(
            config = pluginConfig.gateway,
            listener = gatewayListener,
            scheduler = delayScheduler,
            stats = stats,
            presenceLookup = { uuid -> presenceService?.isOnline(uuid) ?: false },
            durablePark = durableOutbox,
            log = { message -> plugin.logger.warning(message) },
        )
        gatewayClient = client
        gatewayLink = GatewayLink(client)

        // ops state frames ride the same link: no sink registered meant every
        // opsBus.publish (STARTING/RUNNING/RESTARTING/...) only hit the log
        // and the backend never saw a state at all
        ops.bus.register(OpsStatusSink { id, type, payload, correlationId ->
            client.sendRaw(id, type, payload, correlationId)
        })

        val presence = PlayerPresenceService(plugin, client, { ctx.activeSpecSessions() })
        presenceService = presence
        plugin.server.pluginManager.registerEvents(presence, plugin)
    }

    /** Final boot phase: the heartbeat cadence plus the actual connect. */
    fun start() {
        val presence = checkNotNull(presenceService) { "presence read before install" }
        presence.startHeartbeat(ctx.pluginConfig.gateway.heartbeatIntervalMs / 1000)
        client.start()
    }

    fun stop() {
        gatewayClient?.stop()
    }

    /** the backend revoked the account: end the local session, folia-correctly */
    private fun revokeDeletedPlayer(minecraftUuid: String) {
        val uuid = runCatching { UUID.fromString(minecraftUuid) }.getOrNull() ?: return
        val player = ctx.plugin.server.getPlayer(uuid) ?: return
        val message = UiFx.joinLines(ctx.i18n.components("account.revoked"))
        // entity work belongs on the entity scheduler, never on ws callbacks
        player.scheduler.run(
            ctx.plugin,
            { task ->
                player.kick(message)
                task.cancel()
            },
            null,
        )
        ctx.logger.info("account deleted, player kicked: $minecraftUuid")
    }
}
