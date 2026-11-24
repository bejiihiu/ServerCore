package kz.bejiihiu.servercore.placeholderapi

import kz.bejiihiu.servercore.guest.LocalAccountCache
import kz.bejiihiu.servercore.ws.AccountDeletedPayload
import kz.bejiihiu.servercore.ws.ApplicationApprovedPayload
import kz.bejiihiu.servercore.ws.ApplicationRejectedPayload
import kz.bejiihiu.servercore.ws.ApplicationSubmittedPayload
import kz.bejiihiu.servercore.ws.AuthDecisionPayload
import kz.bejiihiu.servercore.ws.AuthRequestPayload
import kz.bejiihiu.servercore.ws.GatewayListener
import kz.bejiihiu.servercore.ws.MessageTypes
import kz.bejiihiu.servercore.ws.WsEnvelope
import kz.bejiihiu.servercore.ws.decodePayload
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

/**
 * pumps backend facts into the state cache. sits on the backend gateway's
 * listener fan-out, so callbacks arrive on websocket/async threads — the only
 * thing touched here is the concurrent cache, never a bukkit object.
 */
class StateWsListener(private val cache: ServerStateCache) : GatewayListener {

    override fun onMessage(envelope: WsEnvelope) {
        when (envelope.type) {
            MessageTypes.AUTH_REQUEST -> decode<AuthRequestPayload>(envelope)
                ?.uuidOrNull()?.let(cache::onAuthRequested)

            MessageTypes.AUTH_APPROVED -> decode<AuthDecisionPayload>(envelope)
                ?.uuidOrNull()?.let(cache::onAuthApproved)

            MessageTypes.AUTH_REJECTED, MessageTypes.AUTH_EXPIRED -> decode<AuthDecisionPayload>(envelope)
                ?.uuidOrNull()?.let(cache::onAuthDenied)

            MessageTypes.APPLICATION_SUBMITTED -> decode<ApplicationSubmittedPayload>(envelope)
                ?.uuidOrNull()?.let(cache::onApplicationSubmitted)

            MessageTypes.APPLICATION_APPROVED -> decode<ApplicationApprovedPayload>(envelope)
                ?.uuidOrNull()?.let(cache::onApplicationApproved)

            MessageTypes.APPLICATION_REJECTED -> decode<ApplicationRejectedPayload>(envelope)
                ?.uuidOrNull()?.let(cache::onApplicationRejected)

            MessageTypes.ACCOUNT_DELETED -> decode<AccountDeletedPayload>(envelope)
                ?.uuidOrNull()?.let(cache::onAccountDeleted)
        }
    }

    override fun onConnected() = cache.onBackendConnected()

    override fun onDisconnected() = cache.onBackendDisconnected()

    private inline fun <reified T> decode(envelope: WsEnvelope): T? =
        runCatching { envelope.decodePayload<T>() }
            .onFailure { System.err.println("servercore: bad ${envelope.type} payload for placeholders, dropped ($it)") }
            .getOrNull()

    private fun Any.uuidOrNull(): UUID? = when (this) {
        is AuthRequestPayload -> runCatching { UUID.fromString(minecraftUuid) }.getOrNull()
        is AuthDecisionPayload -> runCatching { UUID.fromString(minecraftUuid) }.getOrNull()
        is ApplicationSubmittedPayload -> runCatching { UUID.fromString(minecraftUuid) }.getOrNull()
        is ApplicationApprovedPayload -> runCatching { UUID.fromString(minecraftUuid) }.getOrNull()
        is ApplicationRejectedPayload -> runCatching { UUID.fromString(minecraftUuid) }.getOrNull()
        is AccountDeletedPayload -> runCatching { UUID.fromString(minecraftUuid) }.getOrNull()
        else -> null
    }
}

/**
 * join/quit side of the cache, plus the seed from the durable account cache
 * the guest stack maintains. MONITOR priority: pure bookkeeping, never vetoes
 * anything, and runs on the player's region thread — only strings and the
 * concurrent map are touched.
 */
class StatePlayerListener(
    private val cache: ServerStateCache,
    private val accountCache: LocalAccountCache,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        cache.onJoined(player.uniqueId, player.name)
        // known accounts look sane immediately after a restart instead of
        // flashing "unknown" until the first backend event arrives
        accountCache.get(player.uniqueId)?.let { cached ->
            cache.onCachedAccount(player.uniqueId, cached.status, cached.updatedAt)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = cache.onLeft(event.player.uniqueId)
}
