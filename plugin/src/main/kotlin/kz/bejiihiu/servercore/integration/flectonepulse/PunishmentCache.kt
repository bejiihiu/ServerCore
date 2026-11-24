package kz.bejiihiu.servercore.integration.flectonepulse

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * small concurrent cache in front of the flectonepulse punishment lookup.
 * fp reads go through its own database-backed service, so we keep the count
 * of round trips down without ever serving stale "punished" answers for long:
 * entries expire after a short ttl and the flectonepulse event listener
 * invalidates proactively on player load/quit.
 *
 * a cached entry holds the answer even when it is "no punishment" — that is
 * the common case and the one worth caching most, which is why a hit with a
 * null value is a distinct result from a miss.
 */
class PunishmentCache(
    private val ttlMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** hit carries the answer (possibly "no punishment"), miss means look it up */
    sealed interface Result {
        data class Hit(val value: PunishmentInfo?) : Result
        data object Miss : Result
    }

    private data class Entry(val value: PunishmentInfo?, val expiresAt: Long)

    private data class Key(val uuid: UUID, val kind: PunishmentKind)

    private val entries = ConcurrentHashMap<Key, Entry>()

    /** hit (value may be null = "no punishment") or miss/expiry */
    fun get(uuid: UUID, kind: PunishmentKind): Result {
        val entry = entries[Key(uuid, kind)] ?: return Result.Miss
        if (clock() >= entry.expiresAt) {
            entries.remove(Key(uuid, kind), entry)
            return Result.Miss
        }
        return Result.Hit(entry.value)
    }

    fun put(uuid: UUID, kind: PunishmentKind, value: PunishmentInfo?) {
        entries[Key(uuid, kind)] = Entry(value, clock() + ttlMillis)
    }

    /** data for this player changed upstream — forget every kind */
    fun invalidate(uuid: UUID) {
        entries.keys.removeIf { it.uuid == uuid }
    }

    /** flectonepulse reloaded or a bulk change happened — start fresh */
    fun invalidateAll() {
        entries.clear()
    }

    fun size(): Int = entries.size
}
