package kz.bejiihiu.servercore.spec

import kz.bejiihiu.servercore.websocket.Payload
import java.util.UUID
import java.time.ZoneId

/** why a spec session ended; travels on the wire as-is */
enum class SpecEndReason(val wire: String) {
    NORMAL("NORMAL"),
    FORCED("FORCED"),
    TARGET_DISCONNECTED("TARGET_DISCONNECTED"),
    ADMIN_DISCONNECTED("ADMIN_DISCONNECTED"),
    ERROR("ERROR");

    companion object {
        /** the coordinator's exit vocabulary, rendered for the wire */
        fun fromExit(reason: ExitReason): SpecEndReason = when (reason) {
            ExitReason.COMMAND -> NORMAL
            ExitReason.TARGET_QUIT -> TARGET_DISCONNECTED
            ExitReason.ADMIN_QUIT -> ADMIN_DISCONNECTED
            ExitReason.SERVER_SHUTDOWN -> FORCED
        }
    }
}

/** the ws side of the spec flow; the gateway client implements it */
interface SpecEventSender {
    fun sendSpecStarted(payload: Payload.SpecStarted)
    fun sendSpecEnded(payload: Payload.SpecEnded)
}

/**
 * reporting bridge for observation sessions. the [SessionCoordinator] owns the
 * truth about who is watching whom - conflicts, phases, restores and the live
 * session count - and drives this class on every settled transition; nothing
 * here decides anything and nothing here counts.
 *
 * - every accepted start/end goes to the backend over the websocket (the
 *   backend writes the audit_logs rows) and to the local audit trail
 * - activeCount() forwards the coordinator's own count to the heartbeat
 *   counter that /stats renders
 * - denials (permission, level, aborted starts) land in the local trail only,
 *   the backend never saw a session to deny
 *
 * thread-safety: calls arrive from region/async threads, so the count source
 * is read through a volatile reference and the ws/trail writes are
 * fire-and-forget.
 */
class SpecSessionRegistry(
    private val sender: SpecEventSender,
    private val audit: SpecAuditTrail? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.of("Europe/Moscow"),
) {

    /** the coordinator binds its own count here once it exists */
    @Volatile
    private var countSource: (() -> Int)? = null

    /** wire the single session authority in; until then the count reads zero */
    fun bindActiveCount(source: () -> Int) {
        countSource = source
    }

    /** the coordinator settled a session as ACTIVE: report it upstream */
    fun started(session: SpecSession) {
        sender.sendSpecStarted(
            Payload.SpecStarted(
                adminUuid = session.adminId.toString(),
                targetUuid = session.targetId.toString(),
                sessionId = session.sessionId,
                adminUsername = session.adminName,
                targetUsername = session.targetName,
            ),
        )
        audit?.record(entry(session, SpecAuditEvents.START, durationSec = null, reason = null))
    }

    /** the coordinator ended a previously reported session */
    fun ended(session: SpecSession, reason: ExitReason) {
        val endReason = SpecEndReason.fromExit(reason)
        val durationSec = (clock() - session.startedAtMs).coerceAtLeast(0) / 1000
        sender.sendSpecEnded(
            Payload.SpecEnded(
                adminUuid = session.adminId.toString(),
                targetUuid = session.targetId.toString(),
                sessionId = session.sessionId,
                adminUsername = session.adminName,
                targetUsername = session.targetName,
                durationSec = durationSec,
                reason = endReason.wire,
            ),
        )
        val event = if (endReason == SpecEndReason.NORMAL) SpecAuditEvents.END else SpecAuditEvents.FORCED_END
        audit?.record(entry(session, event, durationSec, endReason.wire))
    }

    /** a start that never became a session: local trail only, no ws frame */
    fun denied(
        adminId: UUID,
        adminName: String,
        targetId: UUID?,
        targetName: String?,
        detail: String,
    ) {
        val ts = clock()
        audit?.record(
            SpecAuditEntry(
                ts = ts,
                time = SpecAuditFiles.timeFor(ts, zone),
                admin = adminId.toString(),
                adminName = adminName,
                target = targetId?.toString(),
                targetName = targetName,
                event = SpecAuditEvents.DENIED,
                sessionId = null,
                reason = detail,
                durationSec = null,
            ),
        )
    }

    /** live sessions, straight from the coordinator - the only place that counts */
    fun activeCount(): Int = countSource?.invoke() ?: 0

    private fun entry(
        session: SpecSession,
        event: String,
        durationSec: Long?,
        reason: String?,
    ): SpecAuditEntry {
        val ts = clock()
        return SpecAuditEntry(
            ts = ts,
            time = SpecAuditFiles.timeFor(ts, zone),
            admin = session.adminId.toString(),
            adminName = session.adminName,
            target = session.targetId.toString(),
            targetName = session.targetName,
            event = event,
            sessionId = session.sessionId,
            reason = reason,
            durationSec = durationSec,
        )
    }
}
