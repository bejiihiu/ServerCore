package kz.bejiihiu.servercore.placeholderapi

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kz.bejiihiu.servercore.bars.BarValueSource

/**
 * expansion value logic against a fake snapshot provider: all six
 * placeholders, the unknown-player case and the stale-flag case. the fake
 * never touches bukkit or the network — same contract the real provider has.
 */
class PlaceholderResolverTest {

    private val uuid = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private var now = 10 * MINUTE

    // deterministic map "translation": key + values rendered visibly
    private val text = PlaceholderText { key, values ->
        if (values.isEmpty()) key else key + "(" + values.joinToString(",") { "${it.first}=${it.second}" } + ")"
    }

    private var backendOnline = true

    private val provider = object : ServerStateSnapshotProvider {
        var state: PlayerStateSnapshot? = null
        override fun snapshot(uuid: UUID): PlayerStateSnapshot? = state
        override fun snapshotByName(username: String): PlayerStateSnapshot? = state
        override fun backendOnline(): Boolean = backendOnline
        override fun now(): Long = now
    }

    private fun resolver(
        unknown: String = "unknown",
        staleAfterMin: Long = 10,
        staleSuffix: String = " (stale)",
        ttl: Long = 0, // memoization off by default, tested separately
    ) = PlaceholderResolver(
        provider = provider,
        config = PlaceholderConfig(unknown, staleAfterMin * 60_000, staleSuffix, ttl),
        strings = text,
    )

    private fun state(
        accountStatus: String? = null,
        authApproved: Boolean = false,
        authPending: Boolean = false,
        applicationStatus: String? = null,
        telegramLinked: Boolean = false,
        online: Boolean = false,
        sessionStartAt: Long? = null,
        lastSeenAt: Long? = null,
        backendUpdatedAt: Long? = null,
    ) = PlayerStateSnapshot(
        uuid = uuid,
        username = "steve",
        accountStatus = accountStatus,
        authApproved = authApproved,
        authPending = authPending,
        applicationStatus = applicationStatus,
        telegramLinked = telegramLinked,
        online = online,
        sessionStartAt = sessionStartAt,
        lastSeenAt = lastSeenAt,
        backendUpdatedAt = backendUpdatedAt,
        updatedAt = now,
    )

    @Test
    fun `unknown player renders the configured unknown value`() {
        val r = resolver(unknown = "nobody")
        provider.state = null
        PlaceholderResolverParams.all.forEach { param ->
            assertEquals("nobody", r.resolve(param, uuid))
        }
    }

    @Test
    fun `not-our params resolve to null`() {
        assertNull(resolver().resolve("some_other_plugin_placeholder", uuid))
        assertNull(resolver().resolve("player_name", uuid))
    }

    @Test
    fun `status reflects the account state`() {
        val r = resolver()
        provider.state = state(accountStatus = "ACTIVE", authApproved = true, online = true)
        assertEquals("placeholders.status.active", r.resolve("status", uuid))
        provider.state = state(accountStatus = "BANNED")
        assertEquals("placeholders.status.banned", r.resolve("status", uuid))
        provider.state = state(online = true, authPending = true)
        assertEquals("placeholders.status.guest", r.resolve("status", uuid))
        // known player, no facts at all
        provider.state = state()
        assertEquals("unknown", r.resolve("status", uuid))
    }

    @Test
    fun `authenticated is a yes-no flag`() {
        val r = resolver()
        provider.state = state(authApproved = true)
        assertEquals("placeholders.yes", r.resolve("authenticated", uuid))
        provider.state = state(accountStatus = "ACTIVE") // known active account counts too
        assertEquals("placeholders.yes", r.resolve("authenticated", uuid))
        provider.state = state(online = true)
        assertEquals("placeholders.no", r.resolve("authenticated", uuid))
    }

    @Test
    fun `application status uses the localized verdict`() {
        val r = resolver()
        provider.state = state(applicationStatus = "VOTING")
        assertEquals("placeholders.application.voting", r.resolve("application_status", uuid))
        provider.state = state(applicationStatus = "APPROVED")
        assertEquals("placeholders.application.approved", r.resolve("application_status", uuid))
        provider.state = state(applicationStatus = "REJECTED")
        assertEquals("placeholders.application.rejected", r.resolve("application_status", uuid))
        provider.state = state(applicationStatus = "EXPIRED")
        assertEquals("placeholders.application.expired", r.resolve("application_status", uuid))
        // no application ever seen
        provider.state = state(online = true)
        assertEquals("unknown", r.resolve("application_status", uuid))
    }

    @Test
    fun `telegram linked is a yes-no flag`() {
        val r = resolver()
        provider.state = state(telegramLinked = true)
        assertEquals("placeholders.yes", r.resolve("telegram_linked", uuid))
        provider.state = state()
        assertEquals("placeholders.no", r.resolve("telegram_linked", uuid))
    }

    @Test
    fun `last seen speaks relative time for offline players`() {
        val r = resolver()
        provider.state = state(online = true, lastSeenAt = now)
        assertEquals("placeholders.last-seen.now", r.resolve("last_seen", uuid))

        provider.state = state(online = false, lastSeenAt = now - 5 * MINUTE)
        assertEquals("placeholders.last-seen.minutes(n=5)", r.resolve("last_seen", uuid))

        provider.state = state(online = false, lastSeenAt = now - 3 * HOUR)
        assertEquals("placeholders.last-seen.hours(n=3)", r.resolve("last_seen", uuid))

        provider.state = state(online = false, lastSeenAt = now - 2 * DAY)
        assertEquals("placeholders.last-seen.days(n=2)", r.resolve("last_seen", uuid))

        // quit seconds ago still reads as a full minute, never zero
        provider.state = state(online = false, lastSeenAt = now - 10_000)
        assertEquals("placeholders.last-seen.minutes(n=1)", r.resolve("last_seen", uuid))

        provider.state = state(online = false)
        assertEquals("unknown", r.resolve("last_seen", uuid))
    }

    @Test
    fun `session duration is live time for online players`() {
        val r = resolver()
        provider.state = state(online = true, sessionStartAt = now - (2 * HOUR + 5 * MINUTE + 9_000))
        val value = r.resolve("session_duration", uuid)!!
        assertTrue(value.contains("placeholders.duration.h(n=2)"), value)
        assertTrue(value.contains("placeholders.duration.m(n=05)"), value)
        assertTrue(value.contains("placeholders.duration.s(n=09)"), value)
        provider.state = state(online = false)
        assertEquals("unknown", r.resolve("session_duration", uuid))
    }

    @Test
    fun `backend offline plus old facts appends the stale suffix`() {
        val r = resolver()
        backendOnline = false
        // fact learned 20 minutes ago, tolerance is 10
        provider.state = state(
            accountStatus = "ACTIVE",
            authApproved = true,
            telegramLinked = true,
            backendUpdatedAt = now - 20 * MINUTE,
        )
        assertEquals("placeholders.status.active (stale)", r.resolve("status", uuid))
        assertEquals("placeholders.yes (stale)", r.resolve("authenticated", uuid))
        assertEquals("placeholders.yes (stale)", r.resolve("telegram_linked", uuid))
        // local facts never go stale: we saw the join and the quit ourselves
        provider.state = provider.state!!.copy(
            online = false,
            lastSeenAt = now - 5 * MINUTE,
            applicationStatus = null,
        )
        assertEquals("placeholders.last-seen.minutes(n=5)", r.resolve("last_seen", uuid))
        assertEquals("unknown", r.resolve("application_status", uuid))
    }

    @Test
    fun `fresh facts or a live backend carry no suffix`() {
        val r = resolver()
        backendOnline = false
        provider.state = state(accountStatus = "ACTIVE", backendUpdatedAt = now - MINUTE)
        assertEquals("placeholders.status.active", r.resolve("status", uuid))
        backendOnline = true
        provider.state = provider.state!!.copy(backendUpdatedAt = now - 99 * MINUTE)
        assertEquals("placeholders.status.active", r.resolve("status", uuid))
    }

    @Test
    fun `values are memoized inside the ttl window`() {
        val r = resolver(ttl = 60_000)
        provider.state = state(online = true, sessionStartAt = now)
        val first = r.resolve("session_duration", uuid)
        now += 30_000 // session grew, but the memo window is still open
        assertEquals(first, r.resolve("session_duration", uuid))
        now += 31_000 // ttl passed, recompute picks up the new state
        assertTrue(r.resolve("session_duration", uuid) != first)
    }

    @Test
    fun `params are case-insensitive`() {
        val r = resolver()
        provider.state = state(authApproved = true)
        assertEquals(r.resolve("authenticated", uuid), r.resolve("AUTHENTICATED", uuid))
    }

    @Test
    fun `bar placeholders read the rendered snapshot layer`() {
        val values = mapOf(
            "ram" to "42",
            "tps" to "19.8",
            "mspt" to "38.4",
            "players" to "7",
            "net" to "3/3",
        )
        val barSource = BarValueSource { bar, _ ->
            values[bar.key]
        }
        val r = PlaceholderResolver(
            provider = provider,
            config = PlaceholderConfig("unknown", 10 * 60_000, " (stale)", 0),
            strings = text,
            bars = barSource,
        )

        assertEquals("42", r.resolve("bar_ram", uuid))
        assertEquals("19.8", r.resolve("bar_tps", uuid))
        assertEquals("38.4", r.resolve("bar_mspt", uuid))
        assertEquals("7", r.resolve("bar_players", uuid))
        assertEquals("3/3", r.resolve("bar_net", uuid))
    }

    @Test
    fun `a missing bar reading falls back to the unknown value`() {
        val emptySource = BarValueSource { _, _ -> null }
        val r = PlaceholderResolver(
            provider = provider,
            config = PlaceholderConfig("unknown", 10 * 60_000, " (stale)", 0),
            strings = text,
            bars = emptySource,
        )
        provider.state = null

        assertEquals("unknown", r.resolve("bar_tps", uuid))
        // the expansion is off entirely: same answer, no crash
        val off = resolver()
        assertEquals("unknown", off.resolve("bar_net", uuid))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}

/** the six documented params, shared by the unknown-player sweep */
private object PlaceholderResolverParams {
    val all = listOf(
        "status",
        "authenticated",
        "application_status",
        "telegram_linked",
        "last_seen",
        "session_duration",
    )
}
