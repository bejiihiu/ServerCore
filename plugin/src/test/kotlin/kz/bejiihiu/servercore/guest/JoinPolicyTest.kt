package kz.bejiihiu.servercore.guest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * the join decision matrix, one row per hardening edge case:
 * duplicate joins, in-flight approval races, auth-exempt rejoin, limits.
 */
class JoinPolicyTest {

    private fun decide(
        status: AccountStatus = AccountStatus.GuestPendingLogin(null),
        sessionExists: Boolean = false,
        alreadyAuthorized: Boolean = false,
        sessionsUsed: Int = 0,
        sessionLimit: Int = 3,
        kickAfterLimit: Boolean = true,
        storedSnapshot: Boolean = false,
        guestEnabled: Boolean = true,
    ) = JoinPolicy.decide(
        status, sessionExists, alreadyAuthorized, sessionsUsed,
        sessionLimit, kickAfterLimit, storedSnapshot, guestEnabled,
    )

    @Test
    fun `fresh join without history starts a guest session`() {
        assertEquals(JoinAction.StartGuest(restoreStored = false, overLimit = false), decide())
    }

    @Test
    fun `rejoin reuses the stored snapshot instead of capturing a spectator point`() {
        assertEquals(
            JoinAction.StartGuest(restoreStored = true, overLimit = false),
            decide(storedSnapshot = true),
        )
    }

    @Test
    fun `double join keeps the existing session, no second create and no second burn`() {
        assertEquals(JoinAction.KeepExisting, decide(sessionExists = true))
        assertEquals(JoinAction.KeepExisting, decide(sessionExists = true, storedSnapshot = true))
    }

    @Test
    fun `live telegram approval beats a stale pending-login resolution`() {
        assertEquals(
            JoinAction.Ungate,
            decide(alreadyAuthorized = true),
        )
        // even a late deny cannot re-kick a player the backend just approved
        assertEquals(
            JoinAction.Ungate,
            decide(status = AccountStatus.Denied(DenyCode.BANNED), alreadyAuthorized = true),
        )
    }

    @Test
    fun `authorized verdict ungates without starting a session or burning budget`() {
        assertEquals(JoinAction.Ungate, decide(status = AccountStatus.Authorized))
        // the auth-exempt rejoin case: budget stays untouched whatever the count
        assertEquals(JoinAction.Ungate, decide(status = AccountStatus.Authorized, sessionsUsed = 3))
    }

    @Test
    fun `over limit kicks when configured`() {
        assertEquals(
            JoinAction.KickOverLimit,
            decide(sessionsUsed = 3, sessionLimit = 3, kickAfterLimit = true),
        )
    }

    @Test
    fun `over limit warns but continues when kick is off`() {
        assertEquals(
            JoinAction.StartGuest(restoreStored = false, overLimit = true),
            decide(sessionsUsed = 3, sessionLimit = 3, kickAfterLimit = false),
        )
    }

    @Test
    fun `the join right at the limit is still allowed`() {
        assertEquals(
            JoinAction.StartGuest(restoreStored = false, overLimit = false),
            decide(sessionsUsed = 2, sessionLimit = 3),
        )
    }

    @Test
    fun `guest mode off kicks with the register message`() {
        assertEquals(JoinAction.KickDisabled, decide(guestEnabled = false))
    }

    @Test
    fun `denied account kicks with its code`() {
        assertEquals(JoinAction.KickDenied(DenyCode.BANNED), decide(status = AccountStatus.Denied(DenyCode.BANNED)))
    }

    @Test
    fun `a second client of an authorized account ungates instead of staying restricted`() {
        assertEquals(JoinAction.Ungate, decide(status = AccountStatus.Authorized, sessionExists = true))
        // the approval latch outranks the session slot too
        assertEquals(JoinAction.Ungate, decide(alreadyAuthorized = true, sessionExists = true))
    }

    @Test
    fun `a guest's second client keeps the running session and never burns again`() {
        assertEquals(JoinAction.KeepExisting, decide(sessionExists = true))
        // the budget being spent cannot kick a client that is taking over a
        // session already granted to the same uuid
        assertEquals(
            JoinAction.KeepExisting,
            decide(sessionExists = true, sessionsUsed = 3, sessionLimit = 3, kickAfterLimit = true),
        )
        // turning guest mode off does not evict a session already in progress
        assertEquals(JoinAction.KeepExisting, decide(sessionExists = true, guestEnabled = false))
    }

    @Test
    fun `a denied account cannot take over a running session`() {
        assertEquals(
            JoinAction.KickDenied(DenyCode.BANNED),
            decide(status = AccountStatus.Denied(DenyCode.BANNED), sessionExists = true),
        )
    }
}
