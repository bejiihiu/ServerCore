package kz.bejiihiu.servercore.placeholderapi

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * in-memory state behind the placeholders. fed from three directions:
 *
 * - websocket events (auth verdicts, application verdicts, account deletion)
 *   via [StateWsListener] — these stamp [PlayerStateSnapshot.backendUpdatedAt]
 * - join/quit lifecycle events via [StatePlayerListener] — local facts
 * - the durable account cache the guest stack already maintains, seeded on
 *   join so known players look sane straight after a restart
 *
 * reads are lock-free map lookups; writes are copy-on-put, so a region thread
 * rendering a placeholder never sees a torn snapshot. no io, no network, no
 * scheduling — that discipline is what keeps placeholder rendering cheap and
 * is mandatory for anything added here later.
 */
class ServerStateCache(
    private val clock: () -> Long = System::currentTimeMillis,
    /** hard bound so long-lived servers can't grow the map forever */
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : ServerStateSnapshotProvider {

    private val entries = ConcurrentHashMap<UUID, PlayerStateSnapshot>()

    /** username -> uuid, maintained opportunistically, never authoritative */
    private val byName = ConcurrentHashMap<String, UUID>()

    @Volatile
    private var backendUp = false

    // ---- ServerStateSnapshotProvider ----

    override fun snapshot(uuid: UUID): PlayerStateSnapshot? = entries[uuid]

    override fun snapshotByName(username: String): PlayerStateSnapshot? =
        byName[username.lowercase()]?.let { entries[it] }

    override fun backendOnline(): Boolean = backendUp

    override fun now(): Long = clock()

    // ---- websocket-fed facts (backend-stamped) ----

    fun onBackendConnected() {
        backendUp = true
    }

    fun onBackendDisconnected() {
        backendUp = false
    }

    /** the backend asks this session to wait for a telegram confirmation */
    fun onAuthRequested(uuid: UUID) = mutate(uuid, null, backendStamped = true) {
        it.copy(authPending = true, authApproved = false)
    }

    /** telegram confirmation accepted: durable facts, not session state */
    fun onAuthApproved(uuid: UUID) = mutate(uuid, null, backendStamped = true) {
        it.copy(
            authApproved = true,
            authPending = false,
            accountStatus = STATE_ACTIVE,
            telegramLinked = true,
        )
    }

    /** confirmation denied or the one-time token expired: back to guest */
    fun onAuthDenied(uuid: UUID) = mutate(uuid, null, backendStamped = true) {
        it.copy(authPending = false, authApproved = false)
    }

    fun onApplicationSubmitted(uuid: UUID) = mutate(uuid, null, backendStamped = true) {
        it.copy(applicationStatus = APP_VOTING, telegramLinked = true)
    }

    fun onApplicationApproved(uuid: UUID) = mutate(uuid, null, backendStamped = true) {
        it.copy(applicationStatus = APP_APPROVED, accountStatus = STATE_ACTIVE, telegramLinked = true)
    }

    fun onApplicationRejected(uuid: UUID) = mutate(uuid, null, backendStamped = true) {
        it.copy(applicationStatus = APP_REJECTED)
    }

    /** the voting window closed without quorum, the application is dead */
    fun onApplicationExpired(uuid: UUID) = mutate(uuid, null, backendStamped = true) {
        it.copy(applicationStatus = APP_EXPIRED)
    }

    /** the account is gone: every backend fact we held about it dies with it */
    fun onAccountDeleted(uuid: UUID) = mutate(uuid, null, backendStamped = true) {
        it.copy(
            accountStatus = null,
            authApproved = false,
            authPending = false,
            applicationStatus = null,
            telegramLinked = false,
        )
    }

    /**
     * seed from the durable account cache the guest stack maintains. the cache
     * entry carries the timestamp the backend fact was learned at, so freshness
     * reporting stays honest across restarts.
     */
    fun onCachedAccount(uuid: UUID, status: String, learnedAt: Long) = mutate(uuid, null) {
        when (status.uppercase()) {
            STATE_ACTIVE -> it.copy(
                accountStatus = STATE_ACTIVE,
                telegramLinked = true,
                backendUpdatedAt = learnedAt,
            )
            STATE_BANNED -> it.copy(accountStatus = STATE_BANNED, backendUpdatedAt = learnedAt)
            else -> it
        }
    }

    // ---- local lifecycle facts ----

    fun onJoined(uuid: UUID, username: String) {
        val now = clock()
        mutate(uuid, username) {
            it.copy(online = true, sessionStartAt = now, lastSeenAt = now)
        }
    }

    fun onLeft(uuid: UUID) {
        val now = clock()
        mutate(uuid, null) {
            it.copy(online = false, sessionStartAt = null, lastSeenAt = now)
        }
    }

    // ---- internals ----

    /** copy-on-put update; [username] refreshes the name index when present */
    private fun mutate(
        uuid: UUID,
        username: String?,
        backendStamped: Boolean = false,
        transform: (PlayerStateSnapshot) -> PlayerStateSnapshot,
    ) {
        val now = clock()
        entries.compute(uuid) { _, existing ->
            val base = existing ?: freshEntry(uuid, username)
            val transformed = transform(base)
            transformed.copy(
                backendUpdatedAt = if (backendStamped) now else transformed.backendUpdatedAt,
                updatedAt = now,
            )
        }
        if (username != null) byName[username.lowercase()] = uuid
        evictIfNeeded()
    }

    private fun freshEntry(uuid: UUID, username: String?) = PlayerStateSnapshot(
        uuid = uuid,
        username = username ?: uuid.toString(),
        accountStatus = null,
        authApproved = false,
        authPending = false,
        applicationStatus = null,
        telegramLinked = false,
        online = false,
        sessionStartAt = null,
        lastSeenAt = null,
        backendUpdatedAt = null,
        updatedAt = clock(),
    )

    /** bounded growth: when full, the least recently touched entry goes first */
    private fun evictIfNeeded() {
        if (entries.size <= maxEntries) return
        val oldest = entries.values.minByOrNull { it.updatedAt }?.uuid ?: return
        entries.remove(oldest)?.let { evicted ->
            byName.remove(evicted.username.lowercase(), oldest)
        }
    }

    private fun ConcurrentHashMap<String, UUID>.remove(key: String, expected: UUID) {
        if (this[key] == expected) remove(key)
    }

    companion object {
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_BANNED = "BANNED"
        const val APP_VOTING = "VOTING"
        const val APP_APPROVED = "APPROVED"
        const val APP_REJECTED = "REJECTED"
        const val APP_EXPIRED = "EXPIRED"

        const val DEFAULT_MAX_ENTRIES = 10_000
    }
}
