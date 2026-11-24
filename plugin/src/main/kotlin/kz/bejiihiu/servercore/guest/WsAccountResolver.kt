package kz.bejiihiu.servercore.guest

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kz.bejiihiu.servercore.ws.AccountDeletedPayload
import kz.bejiihiu.servercore.ws.AuthDecisionPayload
import kz.bejiihiu.servercore.ws.AuthRequestPayload
import kz.bejiihiu.servercore.ws.BackendGateway
import kz.bejiihiu.servercore.ws.GatewayListener
import kz.bejiihiu.servercore.ws.MessageTypes
import kz.bejiihiu.servercore.ws.PlayerJoinedPayload
import kz.bejiihiu.servercore.ws.WsEnvelope
import kz.bejiihiu.servercore.ws.decodePayload
import kz.bejiihiu.servercore.ws.messageEnvelope

/**
 * resolves account state through the backend: player_joined goes out, the
 * backend answers with an auth verdict for that uuid. no answer within the
 * timeout means guest (fail closed), exactly like the documented outage
 * posture — a dead backend never grants access.
 *
 * also remembers durable account facts in the local cache and keeps the latest
 * personal deep link per player so the guest prompt can carry a one-time token.
 */
class WsAccountResolver(
    private val gateway: BackendGateway,
    private val cache: LocalAccountCache,
    private val config: GuestConfig,
) : AccountStateResolver, GuestLinkSource, GatewayListener {

    private val waiting = ConcurrentHashMap<UUID, CompletableFuture<AccountStatus>>()
    private val pendingLinks = ConcurrentHashMap<UUID, GuestLink>()
    private val authApprovedListeners = CopyOnWriteArrayList<(UUID) -> Unit>()
    private val linkAvailableListeners = CopyOnWriteArrayList<(UUID) -> Unit>()

    init {
        gateway.addListener(this)
    }

    /** fired when the backend confirms a login, the session service ungates the player */
    fun onAuthApproved(listener: (UUID) -> Unit) {
        authApprovedListeners.add(listener)
    }

    /** fired when a personal deep link arrives while the guest prompt may already be out */
    fun onLinkAvailable(listener: (UUID) -> Unit) {
        linkAvailableListeners.add(listener)
    }

    override fun pendingLink(uuid: UUID): GuestLink? =
        pendingLinks[uuid]?.takeIf { it.fresh(config.linkTtlMs) }

    override fun resolve(uuid: UUID, username: String): CompletableFuture<AccountStatus> {
        // one auth request per uuid: a duplicate login rides the in-flight
        // check instead of making the backend hand out a second deep link
        val existing = waiting[uuid]
        if (existing != null) return existing
        val future = CompletableFuture<AccountStatus>()
        val raced = waiting.putIfAbsent(uuid, future)
        if (raced != null) return raced
        val sent = gateway.send(messageEnvelope(MessageTypes.PLAYER_JOINED, PlayerJoinedPayload(uuid.toString(), username)))
        if (!sent) {
            waiting.remove(uuid, future)
            // link down mid-join: not an error, the caller falls back to the local resolver
            future.completeExceptionally(IllegalStateException("backend link is down"))
            return future
        }
        // timeout leaves the entry behind otherwise; clean up whichever way we finish
        future.whenComplete { _, _ -> waiting.remove(uuid, future) }
        return future.orTimeout(config.resolveTimeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun onMessage(envelope: WsEnvelope) {
        when (envelope.type) {
            MessageTypes.AUTH_REQUEST -> decode<AuthRequestPayload>(envelope)?.let { onAuthRequest(it) }
            MessageTypes.AUTH_APPROVED -> decode<AuthDecisionPayload>(envelope)?.let { onApproved(it) }
            MessageTypes.AUTH_REJECTED, MessageTypes.AUTH_EXPIRED ->
                decode<AuthDecisionPayload>(envelope)?.let { release(it.minecraftUuid, AccountStatus.GuestPendingLogin(null)) }
            MessageTypes.ACCOUNT_DELETED -> decode<AccountDeletedPayload>(envelope)?.let { onAccountDeleted(it) }
        }
    }

    private fun onAuthRequest(payload: AuthRequestPayload) {
        val uuid = parseUuid(payload.minecraftUuid) ?: return
        val url = payload.link
            ?: payload.token?.let { token -> "https://t.me/${payload.botUsername ?: config.botUsername}?start=$token" }
        if (url != null) {
            pendingLinks[uuid] = GuestLink(url, System.currentTimeMillis())
            linkAvailableListeners.forEach { runCatching { it(uuid) } }
        }
        release(payload.minecraftUuid, AccountStatus.GuestPendingLogin(url))
    }

    private fun onApproved(payload: AuthDecisionPayload) {
        val uuid = parseUuid(payload.minecraftUuid) ?: return
        // durable fact: this account exists and reached us through the backend
        cache.put(uuid, AccountStateMapper.STATE_ACTIVE)
        // the personal link was one-time, its job is done either way
        pendingLinks.remove(uuid)
        release(payload.minecraftUuid, AccountStatus.Authorized)
        authApprovedListeners.forEach { runCatching { it(uuid) } }
    }

    private fun onAccountDeleted(payload: AccountDeletedPayload) {
        val uuid = parseUuid(payload.minecraftUuid) ?: return
        cache.remove(uuid)
        pendingLinks.remove(uuid)
        release(payload.minecraftUuid, AccountStatus.GuestPendingLogin(null))
    }

    private fun release(uuidString: String, status: AccountStatus) {
        val uuid = parseUuid(uuidString) ?: return
        waiting.remove(uuid)?.complete(status)
    }

    private inline fun <reified T> decode(envelope: WsEnvelope): T? =
        runCatching { envelope.decodePayload<T>() }
            .onFailure { System.err.println("servercore: bad ${envelope.type} payload, dropped ($it)") }
            .getOrNull()

    private fun parseUuid(raw: String): UUID? = runCatching { UUID.fromString(raw) }.getOrNull()
}
