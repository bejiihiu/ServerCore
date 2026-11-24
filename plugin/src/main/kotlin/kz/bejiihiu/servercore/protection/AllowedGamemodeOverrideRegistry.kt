package kz.bejiihiu.servercore.protection

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.GameMode

/**
 * sanctioned gamemode overrides: which player may currently sit in which
 * non-survival mode and who vouches for it. only explicit grants made by
 * servercore's own flows count (guest sessions, /spec, internal transitions)
 * — op status is deliberately not a sanction.
 *
 * thread-safe: grants are written from any scheduler thread, read from the
 * region thread the gamemode event fires on. the clock is injectable so the
 * expiry semantics are testable.
 */
class AllowedGamemodeOverrideRegistry(private val clock: () -> Long = System::currentTimeMillis) {

    data class Grant(
        val uuid: UUID,
        val modes: Set<GameMode>,
        val source: String,
        /** null = stays until revoked, otherwise a wall-clock deadline */
        val expiresAtMillis: Long?,
    )

    private val grants = ConcurrentHashMap<UUID, MutableSet<Grant>>()

    fun grant(uuid: UUID, modes: Set<GameMode>, source: String, ttlMillis: Long? = null) {
        require(modes.isNotEmpty()) { "grant needs at least one gamemode" }
        val expiresAt = ttlMillis?.let { clock() + it }
        val grant = Grant(uuid, modes.toSet(), source, expiresAt)
        grants.getOrPut(uuid) { ConcurrentHashMap.newKeySet() }.add(grant)
    }

    fun revoke(uuid: UUID): Boolean = grants.remove(uuid)?.isNotEmpty() == true

    /** drop only the grants of one source, e.g. when a guest session ends */
    fun revoke(uuid: UUID, source: String): Boolean {
        val own = grants[uuid] ?: return false
        val removed = own.removeIf { it.source == source }
        if (own.isEmpty()) grants.remove(uuid, own)
        return removed
    }

    fun isSanctioned(uuid: UUID, mode: GameMode): Boolean = mode in sanctionedModes(uuid)

    /** union of the modes every live grant allows, expired grants ignored */
    fun sanctionedModes(uuid: UUID): Set<GameMode> {
        val own = grants[uuid] ?: return emptySet()
        val now = clock()
        val live = own.filter { it.expiresAtMillis == null || it.expiresAtMillis > now }
        if (live.isEmpty()) return emptySet()
        return live.flatMapTo(sortedSetOf()) { it.modes }
    }

    /** drop expired grants, returns how many went away */
    fun sweep(): Int {
        val now = clock()
        var removed = 0
        for ((uuid, own) in grants) {
            if (own.removeIf { it.expiresAtMillis != null && it.expiresAtMillis <= now }) removed++
            if (own.isEmpty()) grants.remove(uuid, own)
        }
        return removed
    }
}
