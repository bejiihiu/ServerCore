package kz.bejiihiu.servercore.guest

import java.util.UUID
import java.util.concurrent.CompletableFuture

/** outcome of the account check that decides what happens to a joining player */
sealed interface AccountStatus {

    /** account known and usable, no guest mode */
    data object Authorized : AccountStatus

    /** no confirmed authorization: guest session with an optional personal deep link */
    data class GuestPendingLogin(val link: String?) : AccountStatus

    /** account may not play at all, [code] picks the kick message */
    data class Denied(val code: String) : AccountStatus
}

/** durable account facts the plugin learned from the backend and keeps locally */
object AccountStateMapper {

    const val STATE_ACTIVE = "ACTIVE"
    const val STATE_BANNED = "BANNED"

    /**
     * last known backend state -> join decision. active accounts keep playing
     * through short backend outages, everything unknown fails closed to guest.
     */
    fun fromCached(state: String?): AccountStatus = when (state?.uppercase()) {
        STATE_ACTIVE -> AccountStatus.Authorized
        STATE_BANNED -> AccountStatus.Denied(DenyCode.BANNED)
        else -> AccountStatus.GuestPendingLogin(null)
    }
}

/** kick reason codes, mapped to player-facing text in the message service */
object DenyCode {
    const val BANNED = "BANNED"
}

/**
 * a personal telegram deep link handed out by the backend for one guest
 * session. tokens are one-time and short lived, so links expire.
 */
data class GuestLink(val url: String, val receivedAt: Long) {
    fun fresh(ttlMs: Long, now: Long = System.currentTimeMillis()): Boolean = now - receivedAt <= ttlMs
}

/**
 * answers one question per join: may this uuid play, and if not, what link
 * should the guest see. implementations must not block the caller; the future
 * is completed off-thread and consumers hop back via the entity scheduler.
 *
 * identity note: this is an offline-mode server, so a uuid is only a nickname
 * derivative and never a security anchor. every authorized verdict must trace
 * back to the backend's telegram binding; the local cache only mirrors what
 * the backend already confirmed.
 */
interface AccountStateResolver {
    fun resolve(uuid: UUID, username: String): CompletableFuture<AccountStatus>
}

/** source of personal deep links received from the backend, empty when offline */
interface GuestLinkSource {
    fun pendingLink(uuid: UUID): GuestLink?

    /** drops the remembered link for a uuid; called when the session it belongs to ends */
    fun forget(uuid: UUID) = Unit
}
