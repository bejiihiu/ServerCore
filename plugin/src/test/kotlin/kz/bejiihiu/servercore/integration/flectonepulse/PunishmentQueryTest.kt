package kz.bejiihiu.servercore.integration.flectonepulse

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.flectone.pulse.model.entity.FPlayer
import net.flectone.pulse.model.event.lifecycle.EnableEvent
import net.flectone.pulse.model.event.lifecycle.ReloadEvent
import net.flectone.pulse.model.event.player.PlayerLoadEvent
import net.flectone.pulse.model.value.Moderation

/** the runtime version guard: one missing sentinel class = incompatible artifact */
class FlectonePulseApiCompatTest {

    @Test
    fun `full surface passes`() {
        val probe = { _: String -> true }
        assertNull(FlectonePulseApiCompat.firstMissingClass(probe))
    }

    @Test
    fun `missing class is reported by name`() {
        val missing = "net.flectone.pulse.service.ModerationService"
        val probe = { name: String -> name != missing }
        assertEquals(missing, FlectonePulseApiCompat.firstMissingClass(probe))
    }

    @Test
    fun `first missing class wins`() {
        val probe = { name: String -> name != "net.flectone.pulse.FlectonePulse" }
        assertEquals("net.flectone.pulse.FlectonePulse", FlectonePulseApiCompat.firstMissingClass(probe))
    }

    @Test
    fun `sentinel list covers every call path the bridge uses`() {
        // these four are load-bearing: entry point, listener plumbing, both
        // read-only services, and the telegram de-dup probe
        listOf(
            "net.flectone.pulse.FlectonePulseAPI",
            "net.flectone.pulse.platform.registry.ListenerRegistry",
            "net.flectone.pulse.service.ModerationService",
            "net.flectone.pulse.module.integration.telegram.TelegramModule",
        ).forEach { assertTrue(it in FlectonePulseApiCompat.REQUIRED_CLASSES, "missing sentinel: $it") }
    }
}

/** the read-only punishment lookup against a fake fp api surface */
class PunishmentQueryTest {

    private val uuid: UUID = UUID.randomUUID()

    // fp's Moderation.isActive() judges expiry against the real system clock,
    // so the fixture timestamps have to sit around real now, not a fixed epoch
    private var now: Long = System.currentTimeMillis()

    private fun queryWith(
        moderation: FakeModerationService,
        ttlMillis: Long = 30_000,
        player: FPlayer = testFPlayer(uuid),
    ): FlectonePulsePunishmentQuery {
        val cache = PunishmentCache(ttlMillis, clock = { now })
        return FlectonePulsePunishmentQuery(
            players = FakeFPlayerService(mapOf(uuid to player)),
            moderation = moderation,
            cache = cache,
            logger = TestLogger.logger,
        )
    }

    @Test
    fun `active timed mute is mapped with remaining time`() {
        val fp = FakeModerationService()
        fp.addValid(uuid, Moderation.Type.MUTE, timedModeration(Moderation.Type.MUTE, now, reason = "spam"))
        val query = queryWith(fp)

        val info = query.activeMute(uuid)
        assertTrue(info != null)
        assertEquals(PunishmentKind.MUTE, info!!.kind)
        assertEquals("spam", info.reason)
        assertFalse(info.permanent)
        // issued 10s ago, runs 100s: ~90s left, minus whatever the jvm spent
        assertTrue(info.remainingMillis in 85_000..90_000)
        assertEquals(now - 10_000, info.issuedAtMillis)
    }

    @Test
    fun `permanent ban maps to a permanent info`() {
        val fp = FakeModerationService()
        fp.addValid(uuid, Moderation.Type.BAN, permanentModeration(Moderation.Type.BAN, now, "griefing"))
        val query = queryWith(fp)

        val info = query.activeBan(uuid)
        assertTrue(info != null)
        assertTrue(info!!.permanent)
        assertEquals(0L, info.remainingMillis)
        assertEquals(PunishmentKind.BAN, info.kind)
    }

    @Test
    fun `expired punishment is not reported`() {
        val fp = FakeModerationService()
        fp.addValid(uuid, Moderation.Type.MUTE, expiredModeration(Moderation.Type.MUTE, now))
        val query = queryWith(fp)

        assertNull(query.activeMute(uuid))
    }

    @Test
    fun `no record means no punishment`() {
        val query = queryWith(FakeModerationService())
        assertNull(query.active(PunishmentKind.WARN, uuid))
        assertFalse(query.hasActive(PunishmentKind.KICK, uuid))
    }

    @Test
    fun `player unknown to flectonepulse means no punishment`() {
        // service has no row for the uuid -> fp hands back its UNKNOWN entity
        val cache = PunishmentCache(30_000, clock = { now })
        val fp = FakeModerationService()
        val query = FlectonePulsePunishmentQuery(FakeFPlayerService(), fp, cache, TestLogger.logger)

        assertNull(query.activeMute(uuid))
        // unknown player short-circuits before the moderation service is touched
        assertEquals(0, fp.lookupCalls)
    }

    @Test
    fun `results are cached until ttl or invalidation`() {
        val fp = FakeModerationService()
        fp.addValid(uuid, Moderation.Type.MUTE, timedModeration(Moderation.Type.MUTE, now, 100_000))
        val cache = PunishmentCache(30_000, clock = { now })
        val query = FlectonePulsePunishmentQuery(
            FakeFPlayerService(mapOf(uuid to testFPlayer(uuid))),
            fp,
            cache,
            TestLogger.logger,
        )

        query.activeMute(uuid)
        query.activeMute(uuid)
        query.activeMute(uuid)
        assertEquals(1, fp.lookupCalls)

        // ttl passes -> fresh lookup
        now += 31_000
        query.activeMute(uuid)
        assertEquals(2, fp.lookupCalls)

        // fp event says data changed -> fresh lookup even inside the ttl
        cache.invalidate(uuid)
        query.activeMute(uuid)
        assertEquals(3, fp.lookupCalls)
    }

    @Test
    fun `negative answers are cached too`() {
        val fp = FakeModerationService()
        val query = queryWith(fp)

        assertNull(query.activeBan(uuid))
        assertNull(query.activeBan(uuid))
        assertEquals(1, fp.lookupCalls)
    }

    @Test
    fun `a failed lookup is not cached as a clean no-punishment`() {
        val fp = FakeModerationService()
        fp.lookupFailure = RuntimeException("db blip")
        val query = queryWith(fp)

        assertNull(query.activeMute(uuid))

        // fp recovered: the next call must re-ask the service, not trust a
        // cached "no punishment" that was really a lookup failure
        fp.lookupFailure = null
        assertNull(query.activeMute(uuid))
        assertEquals(2, fp.lookupCalls)
    }

    @Test
    fun `the read-only boundary holds - no mutation method is ever called`() {
        val fp = FakeModerationService()
        fp.addValid(uuid, Moderation.Type.MUTE, timedModeration(Moderation.Type.MUTE, now, 100_000))
        fp.addValid(uuid, Moderation.Type.BAN, permanentModeration(Moderation.Type.BAN, now))
        val query = queryWith(fp)

        PunishmentKind.entries.forEach { kind -> query.active(kind, uuid) }
        query.activeBan(uuid)
        query.activeMute(uuid)

        assertEquals(0, fp.mutationCalls)
    }
}

/** the fp-side listener keeping servercore's local state in sync */
class FlectonePulseStateListenerTest {

    @Test
    fun `player load invalidates the cached punishment`() {
        val now = System.currentTimeMillis()
        val cache = PunishmentCache(30_000, clock = { now })
        cache.put(UUID.randomUUID(), PunishmentKind.BAN, PunishmentInfo(PunishmentKind.BAN, "r", true, 0, now))

        val player = testFPlayer(UUID.randomUUID())
        FlectonePulseStateListener(cache) {}.onPlayerLoad(PlayerLoadEvent(player))

        assertTrue(cache.get(player.uuid(), PunishmentKind.BAN) is PunishmentCache.Result.Miss)
    }

    @Test
    fun `a cached no-punishment hit is distinct from a miss`() {
        val now = System.currentTimeMillis()
        val cache = PunishmentCache(30_000, clock = { now })
        val uuid = UUID.randomUUID()
        cache.put(uuid, PunishmentKind.MUTE, null)

        // negative answer is cached: a hit with a null value, not a miss
        assertTrue(cache.get(uuid, PunishmentKind.MUTE) is PunishmentCache.Result.Hit)
    }

    @Test
    fun `reload end refreshes everything, reload start does not`() {
        val cache = PunishmentCache(30_000)
        var refreshed = 0
        val listener = FlectonePulseStateListener(cache) { refreshed++ }

        listener.onReload(ReloadEvent(null, ReloadEvent.Type.START))
        assertEquals(0, refreshed)

        listener.onReload(ReloadEvent(null, ReloadEvent.Type.END))
        assertEquals(1, refreshed)
        assertEquals(0, cache.size())
    }

    @Test
    fun `late fp enable triggers the same refresh`() {
        val cache = PunishmentCache(30_000)
        var refreshed = 0
        val listener = FlectonePulseStateListener(cache) { refreshed++ }

        listener.onEnable(EnableEvent(EnableEvent.Type.INIT, null))
        assertEquals(0, refreshed)

        listener.onEnable(EnableEvent(EnableEvent.Type.READY, null))
        assertEquals(1, refreshed)
    }
}

/** jul logger that stays quiet in test output */
object TestLogger {
    val logger: java.util.logging.Logger = java.util.logging.Logger.getLogger("flectonepulse-test").apply {
        useParentHandlers = false
    }
}
