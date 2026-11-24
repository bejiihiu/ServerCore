package kz.bejiihiu.servercore.placeholderapi

import kz.bejiihiu.servercore.util.I18n
import java.util.UUID

/**
 * text source for placeholder output. production wires this to the plugin's
 * [I18n]; tests swap in a plain map. kept as a seam because I18n needs a
 * booted JavaPlugin, which no unit test should have to fake.
 */
fun interface PlaceholderText {

    fun text(key: String, vararg values: Pair<String, String>): String

    companion object {
        /** the real thing: human-readable strings from the messages_*.yml layer */
        fun fromI18n(i18n: I18n): PlaceholderText = PlaceholderText { key, values -> i18n.text(key, *values) }
    }
}

/**
 * pure value logic for the servercore placeholders, deliberately free of any
 * placeholderapi and bukkit types so it stays unit-testable.
 *
 * the one rule this class exists to enforce: everything is answered from the
 * snapshot provider's memory. no database, no http, no blocking call of any
 * kind may ever be added to a placeholder path — placeholders render on
 * foreign threads (chat, tab list, scoreboards) and one blocking call there
 * stalls somebody else's region thread. backend-derived facts carry a stale
 * suffix instead of a re-fetch; that is the whole freshness strategy.
 */
class PlaceholderResolver(
    private val provider: ServerStateSnapshotProvider,
    private val config: PlaceholderConfig,
    private val strings: PlaceholderText,
) {

    /** memoized renders, keyed by "uuid|params" — placeholders can be hit hard */
    private val memo = HashMap<String, Pair<Long, String>>()

    /**
     * resolves one placeholder. returns null when the params are not ours,
     * which is the placeholderapi convention for "unknown placeholder".
     */
    fun resolve(params: String, uuid: UUID?): String? {
        val key = params.lowercase()
        if (key !in KNOWN_PARAMS) return null

        val memoKey = "${uuid ?: "-"}|$key"
        val now = provider.now()
        if (config.cacheTtlMs > 0) {
            synchronized(memo) {
                memo[memoKey]?.let { (renderedAt, value) ->
                    if (now - renderedAt < config.cacheTtlMs) return value
                }
            }
        }

        val value = compute(key, uuid, now)
        if (config.cacheTtlMs > 0) {
            synchronized(memo) {
                if (memo.size >= MEMO_MAX) memo.clear()
                memo[memoKey] = now to value
            }
        }
        return value
    }

    private fun compute(key: String, uuid: UUID?, now: Long): String {
        val snapshot = uuid?.let(provider::snapshot)
        return when (key) {
            "status" -> status(snapshot, now)
            "authenticated" -> authenticated(snapshot, now)
            "application_status" -> applicationStatus(snapshot, now)
            "telegram_linked" -> flag(snapshot?.telegramLinked, snapshot, now)
            "last_seen" -> lastSeen(snapshot, now)
            "session_duration" -> sessionDuration(snapshot, now)
            else -> null // unreachable, key is pre-filtered
        } ?: config.unknownValue
    }

    /** account/authorization state, human-readable, backend-stamped facts may go stale */
    private fun status(snapshot: PlayerStateSnapshot?, now: Long): String? {
        snapshot ?: return null
        val base = when {
            snapshot.accountStatus == ServerStateCache.STATE_BANNED -> strings.text("placeholders.status.banned")
            snapshot.authApproved || snapshot.accountStatus == ServerStateCache.STATE_ACTIVE ->
                strings.text("placeholders.status.active")
            snapshot.authPending || snapshot.online -> strings.text("placeholders.status.guest")
            else -> return null
        }
        return base + staleSuffix(snapshot, now)
    }

    /** yes/no on whether the backend ever confirmed this player's login */
    private fun authenticated(snapshot: PlayerStateSnapshot?, now: Long): String? {
        snapshot ?: return null
        val authenticated = snapshot.authApproved || snapshot.accountStatus == ServerStateCache.STATE_ACTIVE
        return strings.text(if (authenticated) "placeholders.yes" else "placeholders.no") + staleSuffix(snapshot, now)
    }

    /** last known admission verdict, never fetched on demand */
    private fun applicationStatus(snapshot: PlayerStateSnapshot?, now: Long): String? {
        snapshot ?: return null
        val status = snapshot.applicationStatus ?: return null
        val known = setOf(
            ServerStateCache.APP_VOTING,
            ServerStateCache.APP_APPROVED,
            ServerStateCache.APP_REJECTED,
        )
        val base = if (status.uppercase() in known) {
            strings.text("placeholders.application.${status.lowercase()}")
        } else {
            // an unfamiliar verdict is still the most truthful answer we have
            status
        }
        return base + staleSuffix(snapshot, now)
    }

    private fun flag(value: Boolean?, snapshot: PlayerStateSnapshot?, now: Long): String? {
        value ?: return null
        return strings.text(if (value) "placeholders.yes" else "placeholders.no") +
            snapshot?.let { staleSuffix(it, now) }.orEmpty()
    }

    /** local fact, never stale: we were the ones who saw the join and the quit */
    private fun lastSeen(snapshot: PlayerStateSnapshot?, now: Long): String? {
        snapshot ?: return null
        if (snapshot.online) return strings.text("placeholders.last-seen.now")
        val seenAt = snapshot.lastSeenAt ?: return null
        val delta = now - seenAt
        val key = when {
            delta < HOUR_MS -> "placeholders.last-seen.minutes"
            delta < 48 * HOUR_MS -> "placeholders.last-seen.hours"
            else -> "placeholders.last-seen.days"
        }
        val n = when {
            delta < HOUR_MS -> (delta / MINUTE_MS).coerceAtLeast(1)
            delta < 48 * HOUR_MS -> delta / HOUR_MS
            else -> delta / DAY_MS
        }
        return strings.text(key, "n" to n.toString())
    }

    /** live session time; offline players simply have none */
    private fun sessionDuration(snapshot: PlayerStateSnapshot?, now: Long): String? {
        snapshot ?: return null
        val startedAt = snapshot.sessionStartAt ?: return null
        return formatDuration(now - startedAt)
    }

    /** "2h 05m 09s" style, unit labels localized */
    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val parts = buildList {
            if (hours > 0) add(strings.text("placeholders.duration.h", "n" to hours.toString()))
            if (hours > 0 || minutes > 0) add(strings.text("placeholders.duration.m", "n" to "%02d".format(minutes)))
            add(strings.text("placeholders.duration.s", "n" to "%02d".format(seconds)))
        }
        return parts.joinToString(" ")
    }

    /**
     * the freshness flag: only backend-derived facts degrade, and only while
     * the backend link is down. local facts (last seen, session time) are
     * always exactly as fresh as this server's own events.
     */
    private fun staleSuffix(snapshot: PlayerStateSnapshot, now: Long): String {
        val learnedAt = snapshot.backendUpdatedAt ?: return ""
        val stale = !provider.backendOnline() && now - learnedAt > config.staleAfterMs
        return if (stale) config.staleSuffix else ""
    }

    private companion object {
        val KNOWN_PARAMS = setOf(
            "status",
            "authenticated",
            "application_status",
            "telegram_linked",
            "last_seen",
            "session_duration",
        )

        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS

        const val MEMO_MAX = 4_096
    }
}
