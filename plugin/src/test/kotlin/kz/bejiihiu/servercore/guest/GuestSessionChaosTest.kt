package kz.bejiihiu.servercore.guest

import java.nio.file.Path
import java.util.Random
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * the guest session machinery is a set of interlocking promises made to
 * concurrent callers: one live session per uuid, one budget burn per started
 * session, an approval latch that always beats a start and never outlives the
 * quit that ends it, and one durable counter that agrees with all of it after
 * the process restarts.
 *
 * this suite runs randomised lifecycle streams on a real executor pool (plain
 * threads, the same shape as the service's virtual threads) and checks the
 * promises at the end of every stream plus, for the critical ones, at the moment
 * they would be broken. no bukkit, no scheduler: exactly the layer the service
 * delegates its decisions to.
 */
class GuestSessionChaosTest {

    @TempDir
    lateinit var dir: Path

    private fun snapshot(id: String) = GuestSession(
        id, "player",
        PlayerSnapshot("world", 0.0, 64.0, 0.0, 0.0, 0.0, "SURVIVAL", false, 0.1, 0.2),
    )

    /**
     * the service's lifecycle reduced to its pure seams: decide via
     * [JoinPolicy], create inside [GuestSessionRegistry.start], burn the
     * counter inside the same critical section, latch on approve. every
     * callback that must run under the registry mutex registers itself with
     * [enter] so an overlapping pair is caught the moment it happens.
     */
    private inner class Chaos(
        private val uuids: List<UUID>,
        private val sessionLimit: Int = 10,
        private val kickAfterLimit: Boolean = false,
    ) {
        val registry = GuestSessionRegistry()
        val counter = GuestSessionCounter(dir.resolve("counts.json"), Runnable::run).also { it.load() }

        val burns = ConcurrentHashMap<UUID, AtomicInteger>()

        /** burn count at the moment of the last approval reset, read inside the mutex */
        val resetMark = ConcurrentHashMap<UUID, Int>()
        val createdIds = ConcurrentHashMap.newKeySet<String>()
        val violations = ConcurrentHashMap.newKeySet<String>()

        /** critical-section depth per uuid: create bodies and grant bodies share it */
        private val critical = ConcurrentHashMap<UUID, AtomicInteger>()
        private val sessionIds = AtomicInteger()

        private fun enter(uuid: UUID, what: String) {
            val depth = critical.computeIfAbsent(uuid) { AtomicInteger() }.incrementAndGet()
            if (depth != 1) violations.add("$what overlapped another critical section for $uuid (depth $depth)")
        }

        private fun exit(uuid: UUID) {
            critical[uuid]?.decrementAndGet()
        }

        fun join(uuid: UUID) {
            val action = JoinPolicy.decide(
                status = AccountStatus.GuestPendingLogin(null),
                sessionExists = registry.contains(uuid),
                alreadyAuthorized = registry.isAuthorized(uuid),
                sessionsUsed = counter.count(uuid),
                sessionLimit = sessionLimit,
                kickAfterLimit = kickAfterLimit,
                storedSnapshot = false,
                guestEnabled = true,
            )
            when (action) {
                is JoinAction.StartGuest -> {
                    registry.start(uuid, create = {
                        enter(uuid, "create")
                        try {
                            val id = "session-${sessionIds.incrementAndGet()}"
                            burns.computeIfAbsent(uuid) { AtomicInteger() }.incrementAndGet()
                            createdIds.add(id)
                            counter.increment(uuid)
                            snapshot(id)
                        } finally {
                            exit(uuid)
                        }
                    })
                }
                // duplicate login: the new client takes the session over, no burn
                is JoinAction.KeepExisting -> Unit
                is JoinAction.Ungate -> registry.remove(uuid)
                else -> Unit
            }
        }

        /** a player leaves: the latch dies with the session, the budget survives */
        fun quit(uuid: UUID) {
            registry.clearAuthorization(uuid)
            registry.remove(uuid) { true }
        }

        /** the backend approved a login: latch plus budget reset under the mutex */
        fun approve(uuid: UUID) {
            registry.approve(uuid) {
                enter(uuid, "grant")
                try {
                    counter.reset(uuid)
                    resetMark[uuid] = burns[uuid]?.get() ?: 0
                } finally {
                    exit(uuid)
                }
            }
        }

        /** burns since the last approval reset, i.e. the budget the counter must hold */
        fun expectedBudget(uuid: UUID): Int = (burns[uuid]?.get() ?: 0) - (resetMark[uuid] ?: 0)

        fun ungate(uuid: UUID) {
            registry.remove(uuid)
        }

        fun drain() = uuids.forEach { quit(it) }
    }

    /**
     * the duplicate-login path exactly as the service runs it: the second
     * client joins like any other, the verdict goes through [JoinPolicy], and
     * only `KeepExisting` executes the takeover. the counters below are what a
     * regression to an early rebind shortcut would trip over.
     */
    private inner class DuplicateLogin {
        val uuid: UUID = UUID.randomUUID()
        val registry = GuestSessionRegistry()
        val counter = GuestSessionCounter(dir.resolve("dup-counts.json"), Runnable::run).also { it.load() }
        var burns = 0
        var rebinds = 0
        var kicks = 0
        private var ids = 0

        fun join(status: AccountStatus) {
            when (JoinPolicy.decide(
                status = status,
                sessionExists = registry.contains(uuid),
                alreadyAuthorized = registry.isAuthorized(uuid),
                sessionsUsed = counter.count(uuid),
                sessionLimit = 3,
                kickAfterLimit = true,
                storedSnapshot = false,
                guestEnabled = true,
            )) {
                is JoinAction.StartGuest -> registry.start(uuid, create = {
                    burns++
                    counter.increment(uuid)
                    snapshot("session-${++ids}")
                })
                is JoinAction.KeepExisting -> rebinds++
                is JoinAction.Ungate -> registry.remove(uuid)
                is JoinAction.KickOverLimit, is JoinAction.KickDisabled, is JoinAction.KickDenied -> {
                    registry.remove(uuid)
                    kicks++
                }
            }
        }
    }

    @Test
    fun `a guest's second client takes the same session over without a second burn`() {
        val login = DuplicateLogin()
        login.join(AccountStatus.GuestPendingLogin(null))
        val first = login.registry.get(login.uuid)!!

        login.join(AccountStatus.GuestPendingLogin("https://t.me/Bot?start=fresh"))

        assertEquals(first.sessionId, login.registry.get(login.uuid)?.sessionId, "one session, not a second one")
        assertEquals(1, login.burns, "a takeover is not a new session")
        assertEquals(1, login.counter.count(login.uuid))
        assertEquals(1, login.rebinds)
        assertEquals(0, login.kicks)
    }

    @Test
    fun `a denied account never rebinds the session already running`() {
        val login = DuplicateLogin()
        login.join(AccountStatus.GuestPendingLogin(null))
        login.join(AccountStatus.Denied(DenyCode.BANNED))

        assertEquals(0, login.rebinds, "a denied account must not inherit the live session")
        assertEquals(1, login.kicks)
        assertFalse(login.registry.contains(login.uuid), "the denied verdict tears the session down")
        assertEquals(1, login.counter.count(login.uuid), "no second burn on the denied join")
    }

    @Test
    fun `an authorized second client ungates instead of staying a guest`() {
        val login = DuplicateLogin()
        login.join(AccountStatus.GuestPendingLogin(null))
        login.join(AccountStatus.Authorized)

        assertFalse(login.registry.contains(login.uuid), "the session ends in the ungate, not in a takeover")
        assertEquals(0, login.rebinds)
        assertEquals(0, login.kicks)
        assertEquals(1, login.burns)
        assertTrue(login.registry.isAuthorized(login.uuid).not(), "no latch is invented by a resolver verdict")
    }

    @Test
    fun `randomised join quit rebind approve streams keep one session and one burn per start`() {
        val uuids = (1..6).map { UUID.randomUUID() }
        val chaos = Chaos(uuids)
        val pool = Executors.newFixedThreadPool(8)
        val barrier = CyclicBarrier(8)
        try {
            (0 until 8).map { worker ->
                pool.submit(Callable {
                    barrier.await()
                    val random = Random(worker * 7919L + 13)
                    repeat(400) {
                        val uuid = uuids[random.nextInt(uuids.size)]
                        when (random.nextInt(100)) {
                            in 0..49 -> chaos.join(uuid)
                            in 50..74 -> chaos.quit(uuid)
                            in 75..89 -> chaos.approve(uuid)
                            else -> chaos.ungate(uuid)
                        }
                    }
                    true
                })
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        // the only promise that can be broken while the stream runs
        assertTrue(chaos.violations.isEmpty(), "critical sections overlapped: ${chaos.violations}")

        // one live session per uuid, every live session known to the burn accounting
        val live = chaos.registry.all()
        assertEquals(live.size, live.values.map { it.sessionId }.distinct().size, "two uuids shared one session")
        assertTrue(live.keys.all { it in uuids }, "a foreign uuid got a session")
        assertTrue(live.values.all { it.sessionId in chaos.createdIds }, "a live session nobody created")
        assertEquals(chaos.burns.values.sumOf { it.get() }, chaos.createdIds.size, "burns and sessions diverged")

        // the drain is what a shutdown / last quit does for every uuid
        chaos.drain()
        assertEquals(0, chaos.registry.all().size, "a drained registry has no sessions")
        assertTrue(uuids.none { chaos.registry.isAuthorized(it) }, "an approval latch leaked past the drain")

        // the durable counter still agrees with the burns since the last reset
        uuids.forEach { uuid ->
            assertEquals(chaos.expectedBudget(uuid), chaos.counter.count(uuid), "counter diverged for $uuid")
        }

        // and the file on disk agrees with memory after the shutdown flush
        chaos.counter.shutdown()
        val reloaded = GuestSessionCounter(dir.resolve("counts.json"), Runnable::run).also { it.load() }
        uuids.forEach { uuid ->
            assertEquals(chaos.counter.count(uuid), reloaded.count(uuid), "the durable counter lost $uuid")
        }

        // no latch and no session is left, so a fresh join works for everybody
        uuids.forEach { uuid ->
            assertNotNull(chaos.registry.start(uuid) { snapshot("post-drain") }, "uuid $uuid could not start again")
            assertTrue(chaos.registry.remove(uuid) != null)
        }
    }

    @Test
    fun `a create body can never overlap an approval grant`() {
        val uuid = UUID.randomUUID()
        val chaos = Chaos(listOf(uuid), sessionLimit = 100, kickAfterLimit = true)
        val pool = Executors.newFixedThreadPool(6)
        val barrier = CyclicBarrier(6)
        try {
            // join / approve / quit hammered on one uuid: the approval must
            // either land before the start (blocking it) or after it (granting
            // it), never in the middle of the budget burn
            (0 until 6).map { worker ->
                pool.submit(Callable {
                    barrier.await()
                    repeat(400) {
                        when ((worker + it) % 3) {
                            0 -> chaos.join(uuid)
                            1 -> chaos.approve(uuid)
                            else -> chaos.quit(uuid)
                        }
                    }
                    true
                })
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        assertTrue(chaos.violations.isEmpty(), "approval interleaved with a burn: ${chaos.violations}")
        chaos.drain()

        // after the drain the budget is exactly the burns since the last reset:
        // no reset was applied to a burn it never saw, none was skipped
        assertEquals(chaos.expectedBudget(uuid), chaos.counter.count(uuid))
        assertFalse(chaos.registry.isAuthorized(uuid))
        assertFalse(chaos.registry.contains(uuid))
    }

    @Test
    fun `an approval latch never survives the quit that ends it`() {
        val uuid = UUID.randomUUID()
        val chaos = Chaos(listOf(uuid))
        val other = UUID.randomUUID()

        repeat(300) { cycle ->
            chaos.approve(uuid)
            assertTrue(chaos.registry.isAuthorized(uuid), "cycle $cycle: the latch did not take")
            assertNull(
                chaos.registry.start(uuid) { snapshot("late-$cycle") },
                "cycle $cycle: an approved uuid started a guest session",
            )
            assertEquals(0, chaos.counter.count(uuid), "cycle $cycle: approval left a budget behind")

            chaos.quit(uuid)
            assertFalse(chaos.registry.isAuthorized(uuid), "cycle $cycle: the latch outlived the quit")
            assertNotNull(chaos.registry.start(uuid) { snapshot("next-$cycle") }, "cycle $cycle: join blocked")
            chaos.quit(uuid)
        }

        // a second uuid is untouched by the first one's churn
        assertFalse(chaos.registry.isAuthorized(other))
        assertFalse(chaos.registry.contains(other))
    }

    @Test
    fun `the budget counts down against a kick policy without losing a burn`() {
        val uuid = UUID.randomUUID()
        val chaos = Chaos(listOf(uuid), sessionLimit = 3, kickAfterLimit = true)
        val pool = Executors.newFixedThreadPool(6)
        val barrier = CyclicBarrier(6)
        try {
            // join/quit cycles with kick enabled: sessions stop starting once
            // the budget is spent, and no start is ever burned twice
            (0 until 6).map {
                pool.submit(Callable {
                    barrier.await()
                    repeat(300) {
                        chaos.join(uuid)
                        chaos.quit(uuid)
                    }
                    true
                })
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        assertTrue(chaos.violations.isEmpty(), "${chaos.violations}")
        val burns = chaos.burns[uuid]?.get() ?: 0
        // the budget only counts forward and never exceeds what was created
        assertEquals(burns, chaos.counter.count(uuid), "the counter invented or lost burns")
        assertTrue(burns >= 3, "the stream never got past the first sessions")
        // over the limit the policy must have stopped starting sessions: with
        // 1800 join attempts the burn count cannot track the attempt count
        assertTrue(burns < 300, "kick-after-limit never engaged, $burns burns")

        // an approval resets the budget and hands out a fresh set of sessions
        chaos.approve(uuid)
        assertEquals(0, chaos.counter.count(uuid), "approval must reset the budget")
        chaos.quit(uuid)
        chaos.join(uuid)
        assertEquals(1, chaos.counter.count(uuid), "the first session after a reset burns exactly once")
    }
}
