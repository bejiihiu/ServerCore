package kz.bejiihiu.servercore.placeholderapi

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** cache update paths: every fact comes from a simulated event, never a fetch */
class ServerStateCacheTest {

    private val uuid = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private var now = 1_000_000L

    private fun cache(maxEntries: Int = ServerStateCache.DEFAULT_MAX_ENTRIES) =
        ServerStateCache(clock = { now }, maxEntries = maxEntries)

    @Test
    fun `join marks online and starts the session clock`() {
        val cache = cache()
        cache.onJoined(uuid, "steve")
        val s = cache.snapshot(uuid)!!
        assertTrue(s.online)
        assertEquals(now, s.sessionStartAt)
        assertEquals(now, s.lastSeenAt)
        assertEquals("steve", s.username)
        assertEquals(uuid, cache.snapshotByName("STEVE")!!.uuid)
    }

    @Test
    fun `quit stops the session and stamps last seen`() {
        val cache = cache()
        cache.onJoined(uuid, "steve")
        now += 5_000
        cache.onLeft(uuid)
        val s = cache.snapshot(uuid)!!
        assertTrue(!s.online)
        assertNull(s.sessionStartAt)
        assertEquals(1_005_000L, s.lastSeenAt)
    }

    @Test
    fun `auth approval flips the durable facts`() {
        val cache = cache()
        cache.onJoined(uuid, "steve")
        now += 1_000
        cache.onAuthRequested(uuid)
        assertTrue(cache.snapshot(uuid)!!.authPending)
        now += 1_000
        cache.onAuthApproved(uuid)
        val s = cache.snapshot(uuid)!!
        assertTrue(s.authApproved)
        assertTrue(!s.authPending)
        assertEquals(ServerStateCache.STATE_ACTIVE, s.accountStatus)
        assertTrue(s.telegramLinked)
    }

    @Test
    fun `rejection and expiry drop back to unauthenticated`() {
        val cache = cache()
        cache.onAuthApproved(uuid)
        cache.onAuthDenied(uuid)
        val s = cache.snapshot(uuid)!!
        assertTrue(!s.authApproved)
        assertTrue(!s.authPending)
        // the account itself stays known, only the session verdict resets
        assertEquals(ServerStateCache.STATE_ACTIVE, s.accountStatus)
    }

    @Test
    fun `application lifecycle tracks the verdicts`() {
        val cache = cache()
        cache.onApplicationSubmitted(uuid)
        assertEquals(ServerStateCache.APP_VOTING, cache.snapshot(uuid)!!.applicationStatus)
        assertTrue(cache.snapshot(uuid)!!.telegramLinked)
        cache.onApplicationApproved(uuid)
        val approved = cache.snapshot(uuid)!!
        assertEquals(ServerStateCache.APP_APPROVED, approved.applicationStatus)
        assertEquals(ServerStateCache.STATE_ACTIVE, approved.accountStatus)
        cache.onApplicationRejected(UUID.fromString("22222222-2222-4222-8222-222222222222"))
        // nothing leaked to the other player
        assertEquals(ServerStateCache.APP_APPROVED, cache.snapshot(uuid)!!.applicationStatus)
    }

    @Test
    fun `account deletion wipes the backend facts`() {
        val cache = cache()
        cache.onAuthApproved(uuid)
        cache.onApplicationApproved(uuid)
        cache.onAccountDeleted(uuid)
        val s = cache.snapshot(uuid)!!
        assertNull(s.accountStatus)
        assertNull(s.applicationStatus)
        assertTrue(!s.authApproved)
        assertTrue(!s.telegramLinked)
    }

    @Test
    fun `cached account seeds backend facts with the cache timestamp`() {
        val cache = cache()
        cache.onCachedAccount(uuid, "active", learnedAt = 42L)
        val s = cache.snapshot(uuid)!!
        assertEquals(ServerStateCache.STATE_ACTIVE, s.accountStatus)
        assertTrue(s.telegramLinked)
        assertEquals(42L, s.backendUpdatedAt)
    }

    @Test
    fun `backend liveness is tracked for the staleness logic`() {
        val cache = cache()
        assertTrue(!cache.backendOnline())
        cache.onBackendConnected()
        assertTrue(cache.backendOnline())
        cache.onBackendDisconnected()
        assertTrue(!cache.backendOnline())
    }

    @Test
    fun `cache stays bounded by evicting the oldest entry`() {
        val cache = cache(maxEntries = 2)
        val a = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val b = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val c = UUID.fromString("44444444-4444-4444-8444-444444444444")
        cache.onJoined(a, "a")
        now += 1
        cache.onJoined(b, "b")
        now += 1
        cache.onJoined(c, "c") // pushes a out
        assertNull(cache.snapshot(a))
        assertEquals("b", cache.snapshot(b)!!.username)
        assertEquals("c", cache.snapshot(c)!!.username)
    }

    @Test
    fun `unknown player has no snapshot`() {
        assertNull(cache().snapshot(uuid))
        assertNull(cache().snapshotByName("steve"))
    }
}
