package kz.bejiihiu.servercore.guest

/**
 * pure decision for what a join does, extracted from the session service so
 * every race edge case is unit-testable without bukkit. the service only
 * executes what this returns, all ordering questions live here.
 */
sealed interface JoinAction {

    /** authorization stands (live telegram approval or resolver verdict), no guest session */
    data object Ungate : JoinAction

    /** a session already runs for this uuid: duplicate login, the new client takes it over */
    data object KeepExisting : JoinAction

    /** fresh or restored guest session; [overLimit] means the budget is spent but kick is off */
    data class StartGuest(val restoreStored: Boolean, val overLimit: Boolean) : JoinAction

    /** budget spent and kick-after-limit is on */
    data object KickOverLimit : JoinAction

    /** guest mode is off, unauthenticated players do not get in */
    data object KickDisabled : JoinAction

    /** account may not play at all */
    data class KickDenied(val code: String) : JoinAction
}

object JoinPolicy {

    fun decide(
        status: AccountStatus,
        sessionExists: Boolean,
        alreadyAuthorized: Boolean,
        sessionsUsed: Int,
        sessionLimit: Int,
        kickAfterLimit: Boolean,
        storedSnapshot: Boolean,
        guestEnabled: Boolean,
    ): JoinAction {
        // a telegram approval wins cleanly over anything an older resolution
        // path still carries, including a late deny or guest verdict
        if (alreadyAuthorized) return JoinAction.Ungate
        return when (status) {
            is AccountStatus.Authorized -> JoinAction.Ungate
            is AccountStatus.Denied -> JoinAction.KickDenied(status.code)
            is AccountStatus.GuestPendingLogin -> when {
                sessionExists -> JoinAction.KeepExisting
                !guestEnabled -> JoinAction.KickDisabled
                sessionsUsed >= sessionLimit && kickAfterLimit -> JoinAction.KickOverLimit
                else -> JoinAction.StartGuest(
                    restoreStored = storedSnapshot,
                    overLimit = sessionsUsed >= sessionLimit,
                )
            }
        }
    }
}
