package kz.bejiihiu.servercore

import kz.bejiihiu.servercore.config.ConfigLoader
import kz.bejiihiu.servercore.player.PlayerPresenceService
import kz.bejiihiu.servercore.websocket.DelayScheduler
import kz.bejiihiu.servercore.websocket.GatewayClient
import kz.bejiihiu.servercore.websocket.GatewayListener
import kz.bejiihiu.servercore.websocket.Payload
import kz.bejiihiu.servercore.websocket.ServerStatsProvider
import net.kyori.adventure.text.Component
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * single entry point, folia-only. onEnable builds the config, the gateway
 * client and the presence service; all scheduling goes through folia's
 * async/entity schedulers, never the bukkit one.
 */
class ServerCorePlugin : JavaPlugin() {

    private var gateway: GatewayClient? = null
    private var presence: PlayerPresenceService? = null

    override fun onEnable() {
        saveDefaultConfig()
        val config = try {
            ConfigLoader.load(config)
        } catch (e: IllegalArgumentException) {
            // bad config is a boot blocker: better dead than half-connected
            logger.severe("config error: ${e.message}")
            server.pluginManager.disablePlugin(this)
            return
        }

        val stats = object : ServerStatsProvider {
            override fun onlinePlayers(): Int = presence?.onlineCount() ?: 0
            override fun maxPlayers(): Int = server.maxPlayers
            override fun serverVersion(): String = server.getMinecraftVersion()
        }

        val listener = object : GatewayListener {
            override fun onConnected() {
                logger.info("backend gateway connected as ${config.gateway.serverId}")
            }

            override fun onDisconnected() {
                logger.warning("backend gateway disconnected, reconnect scheduled")
            }

            override fun onLinkToken(payload: Payload.LinkToken) {
                // the in-game deep-link delivery lands with the guest phase;
                // the token is valid and single-use either way
                logger.info("link token issued for ${payload.username}")
            }

            override fun onAccountDeleted(payload: Payload.AccountDeleted) {
                revokeDeletedPlayer(payload.minecraftUuid)
            }
        }

        // folia async scheduler drives reconnect delays; the client itself is
        // bukkit-free, so its callbacks may run on any async thread
        val delayScheduler = DelayScheduler { task: Runnable, delayMs: Long ->
            server.asyncScheduler.runDelayed(this, { _ -> task.run() }, delayMs, TimeUnit.MILLISECONDS)
        }

        val client = GatewayClient(
            config = config.gateway,
            listener = listener,
            scheduler = delayScheduler,
            stats = stats,
            presenceLookup = { uuid -> presence?.isOnline(uuid) ?: false },
        )
        gateway = client

        val presenceService = PlayerPresenceService(this, client)
        presence = presenceService
        server.pluginManager.registerEvents(presenceService, this)

        // heartbeat keeps the backend's liveness picture fresh
        presenceService.startHeartbeat()
        client.start()

        logger.info("servercore ${pluginMeta.version} enabled")
    }

    /** backend revoked the account: end the local session, folia-correctly */
    private fun revokeDeletedPlayer(minecraftUuid: String) {
        val uuid = runCatching { UUID.fromString(minecraftUuid) }.getOrNull() ?: return
        val player = server.getPlayer(uuid) ?: return
        // entity work belongs on the entity scheduler, never on ws callbacks
        player.scheduler.run(
            this,
            { task ->
                player.kick(Component.text("Ваш аккаунт был удалён. Доступ к серверу отозван."))
                task.cancel()
            },
            null,
        )
        logger.info("account deleted, player kicked: $minecraftUuid")
    }

    override fun onDisable() {
        gateway?.stop()
        logger.info("servercore ${pluginMeta.version} disabled, bye")
    }
}
