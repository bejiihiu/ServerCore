package kz.bejiihiu.servercore.spec

import kz.bejiihiu.servercore.websocket.Payload
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** why a spec session ended; travels on the wire as-is */
enum class SpecEndReason(val wire: String) {
    NORMAL("NORMAL"),
    FORCED("FORCED"),
    TARGET_DISCONNECTED("TARGET_DISCONNECTED"),
    ADMIN_DISCONNECTED("ADMIN_DISCONNECTED"),
    ERROR("ERROR"),
}

/** outcome of trying to open an observation session */
enum class SpecStartResult {
    STARTED,
    ADMIN_BUSY,
    TARGET_ALREADY_WATCHED,
}

/** the ws side of the spec flow; the gateway client implements it */
interface SpecEventSender {
    fun sendSpecStarted(payload: Payload.SpecStarted)
    fun sendSpecEnded(payload: Payload.SpecEnded)
}

/** one live observation session as the plugin sees it */
data class ActiveSpecSession(
    val adminUuid: UUID,
    val adminName: String,
    val targetUuid: UUID,
    val targetName: String,
    val sessionId: String,
    val startedAtMs: Long,
)

/**
 * registry of active administrative observation sessions. this is the single
 * source of truth for "who is watching whom" on this server:
 *
 * - refuses conflicting sessions — an admin cannot open a second session and
 *   a target cannot be watched by two admins at once
 * - every accepted start/end goes to the backend over the websocket (the
 *   backend writes the audit_logs rows) and to the local audit trail
 * - activeCount() feeds the SERVER_HEARTBEAT counter that /stats renders
 *
 * thread-safety: calls arrive from region threads, so everything is
 * concurrent-map based and the ws/audit writes are fire-and-forget.
 */
class SpecSessionRegistry(
    private val audit: SpecAuditTrail?,
    private val sender: SpecEventSender,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: java.time.ZoneId = java.time.ZoneId.of("Europe/Moscow"),
) {

    private val byAdmin = ConcurrentHashMap<String, ActiveSpecSession>()
    private val watchedTargets = ConcurrentHashMap<String, String>() // target uuid -> admin uuid

    /**
     * opens a session. conflicting sessions are refused, never silently
     * replaced: the caller decides how to tell the admin.
     */
    fun start(
        adminUuid: UUID,
        adminName: String,
        targetUuid: UUID,
        targetName: String,
        sessionId: String,
    ): SpecStartResult {
        val adminKey = adminUuid.toString()
        val targetKey = targetUuid.toString()
        val session = ActiveSpecSession(adminUuid, adminName, targetUuid, targetName, sessionId, clock())

        // claim both slots atomically enough for our purposes: the maps are the
        // lock, and every refused path leaves them untouched
        if (byAdmin.putIfAbsent(adminKey, session) != null) return SpecStartResult.ADMIN_BUSY
        if (watchedTargets.putIfAbsent(targetKey, adminKey) != null) {
            byAdmin.remove(adminKey)
            return SpecStartResult.TARGET_ALREADY_WATCHED
        }

        sender.sendSpecStarted(
            Payload.SpecStarted(
                adminUuid = adminKey,
                targetUuid = targetKey,
                sessionId = sessionId,
                adminUsername = adminName,
                targetUsername = targetName,
            ),
        )
        audit?.record(entry(session, SpecAuditEvents.START, durationSec = null, reason = null))
        return SpecStartResult.STARTED
    }

    /**
     * closes one session by admin + session id. unknown pairs are ignored —
     * ends may arrive twice (target quit races admin quit).
     */
    fun end(adminUuid: UUID, sessionId: String, reason: SpecEndReason): Boolean {
        val adminKey = adminUuid.toString()
        val session = byAdmin[adminKey] ?: return false
        if (session.sessionId != sessionId) return false
        if (!byAdmin.remove(adminKey, session)) return false
        watchedTargets.remove(session.targetUuid.toString(), adminKey)
        finish(session, reason)
        return true
    }

    /**
     * closes every session of one admin (the admin quit case). returns the
     * number of sessions that were actually closed.
     */
    fun endAllForAdmin(adminUuid: UUID, reason: SpecEndReason): Int {
        val adminKey = adminUuid.toString()
        var closed = 0
        for (session in byAdmin.values) {
            if (session.adminUuid != adminUuid) continue
            if (byAdmin.remove(adminKey, session)) {
                watchedTargets.remove(session.targetUuid.toString(), adminKey)
                finish(session, reason)
                closed += 1
            }
        }
        return closed
    }

    /** releases the target-side claim when the target disconnects mid-watch */
    fun endAllForTarget(targetUuid: UUID, reason: SpecEndReason): Int {
        val targetKey = targetUuid.toString()
        var closed = 0
        for (session in byAdmin.values) {
            if (session.targetUuid != targetUuid) continue
            if (byAdmin.remove(session.adminUuid.toString(), session)) {
                watchedTargets.remove(targetKey, session.adminUuid.toString())
                finish(session, reason)
                closed += 1
            }
        }
        return closed
    }

    fun activeCount(): Int = byAdmin.size

    fun activeFor(adminUuid: UUID): ActiveSpecSession? = byAdmin[adminUuid.toString()]

    /** all live sessions, newest first — for admin-facing ui later */
    fun listActive(): List<ActiveSpecSession> = byAdmin.values.sortedByDescending { it.startedAtMs }

    private fun finish(session: ActiveSpecSession, reason: SpecEndReason) {
        val durationSec = (clock() - session.startedAtMs).coerceAtLeast(0) / 1000
        sender.sendSpecEnded(
            Payload.SpecEnded(
                adminUuid = session.adminUuid.toString(),
                targetUuid = session.targetUuid.toString(),
                sessionId = session.sessionId,
                adminUsername = session.adminName,
                targetUsername = session.targetName,
                durationSec = durationSec,
                reason = reason.wire,
            ),
        )
        val event = if (reason == SpecEndReason.NORMAL) SpecAuditEvents.END else SpecAuditEvents.FORCED_END
        audit?.record(entry(session, event, durationSec, reason.wire))
    }

    private fun entry(
        session: ActiveSpecSession,
        event: String,
        durationSec: Long?,
        reason: String?,
    ): SpecAuditEntry {
        val ts = clock()
        return SpecAuditEntry(
            ts = ts,
            time = SpecAuditFiles.timeFor(ts, zone),
            admin = session.adminUuid.toString(),
            adminName = session.adminName,
            target = session.targetUuid.toString(),
            targetName = session.targetName,
            event = event,
            sessionId = session.sessionId,
            reason = reason,
            durationSec = durationSec,
        )
    }
}
