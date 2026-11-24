package kz.bejiihiu.servercore.spec

/**
 * what the rest of the plugin sees of a session's life. the coordinator is the
 * single owner of the truth, everyone else hangs off these callbacks:
 *
 * - [onClaimed] fires under the coordinator lock, before any world touch -
 *   exactly the right moment to sanction the coming gamemode switch, the
 *   protection guard sees the grant before the spectator change event fires
 * - [onStarted] fires once the world accepted the session (camera attached),
 *   the same point the ws/backend report goes out
 * - [onAborted] fires when the world refused a claimed session; nothing was
 *   ever reported as started, but partial world mutations may need undoing
 * - [onEnded] fires for every real exit, started or not
 *
 * all callbacks may run on async/region threads; implementations must be
 * thread-safe and non-blocking (schedule their own entity work).
 */
interface SpecSessionListener {
    fun onClaimed(session: SpecSession) {}
    fun onStarted(session: SpecSession) {}
    fun onAborted(session: SpecSession) {}
    fun onEnded(session: SpecSession, reason: ExitReason) {}
}
