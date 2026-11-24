package kz.bejiihiu.servercore.guest

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class GuestSession(val sessionId: String, val username: String, val snapshot: PlayerSnapshot)

/**
 * per-uuid guest session store. start-if-absent is atomic under one mutex so a
 * duplicate login or two concurrent resolutions can never create two sessions
 * or burn the session budget twice — whatever bookkeeping the create block
 * does (counter increment, snapshot persist) is part of the same critical
 * section, and the approval latch below makes a telegram approval beat an
 * in-flight guest start instead of racing it.
 *
 * no bukkit types here on purpose: the races are unit-tested with plain
 * executors.
 */
class GuestSessionRegistry {

    private val lock = Any()
    private val sessions = ConcurrentHashMap<UUID, GuestSession>()

    /** telegram approval latch: once granted it outlives stale resolutions until quit */
    private val authorized = ConcurrentHashMap.newKeySet<UUID>()

    fun contains(uuid: UUID): Boolean = sessions.containsKey(uuid)

    fun get(uuid: UUID): GuestSession? = sessions[uuid]

    fun all(): Map<UUID, GuestSession> = sessions.toMap()

    fun remove(uuid: UUID): GuestSession? = sessions.remove(uuid)

    fun isAuthorized(uuid: UUID): Boolean = uuid in authorized

    /**
     * grants the approval latch and runs [grant] (counter reset, notification
     * bookkeeping) under the start mutex, so an approval can never interleave
     * with a session start that is mid-way through burning the budget.
     */
    fun approve(uuid: UUID, grant: () -> Unit = {}) {
        synchronized(lock) {
            authorized.add(uuid)
            grant()
        }
    }

    fun clearAuthorization(uuid: UUID) {
        synchronized(lock) { authorized.remove(uuid) }
    }

    /**
     * atomic start-if-absent. returns null when a session already exists or
     * the approval latch says this uuid must not become a guest; [create] and
     * its side effects run inside the mutex exactly once for the winner.
     */
    fun start(uuid: UUID, allowed: () -> Boolean = { true }, create: () -> GuestSession): GuestSession? =
        synchronized(lock) {
            if (sessions.containsKey(uuid) || uuid in authorized || !allowed()) null
            else create().also { sessions[uuid] = it }
        }

    /** runs arbitrary bookkeeping under the same mutex [start] uses */
    fun <T> withStartLock(block: () -> T): T = synchronized(lock) { block() }
}
