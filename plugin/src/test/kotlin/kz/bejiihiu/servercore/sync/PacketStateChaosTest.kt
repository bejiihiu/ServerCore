package kz.bejiihiu.servercore.sync

import java.util.Random
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.logging.Logger
import kz.bejiihiu.servercore.esp.DisguiseAssignment
import kz.bejiihiu.servercore.esp.ViewState
import kz.bejiihiu.servercore.esp.ViewStateApplier
import kz.bejiihiu.servercore.packet.CachedViewStateStore
import kz.bejiihiu.servercore.packet.PacketStateRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * the packet bridge under a hostile transition stream. the failure this suite
 * exists for is fail-open: a pair the engine decided to hide must never appear
 * visible to the client just because a death, a respawn, a teleport, a
 * reconnect, a tracking-out or a reused entity id raced the last verdict.
 *
 * every operation here is one the live server really performs - it is the
 * sequencing, not the code, that gets randomised.
 */
class PacketStateChaosTest {

    private fun viewerId(i: Int) = UUID.nameUUIDFromBytes("chaos-viewer-$i".toByteArray())
    private fun targetId(i: Int) = UUID.nameUUIDFromBytes("chaos-target-$i".toByteArray())

    private class RecordingDisguise : ViewStateApplier {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())

        override fun apply(viewer: UUID, target: UUID, state: ViewState, assignment: DisguiseAssignment?) {
            calls += "$state($viewer,$target)"
        }

        fun forget() = calls.clear()
    }

    /**
     * one live plugin instance worth of state: policy rows, the applied packet
     * picture, the identity tracker and the sync coordinator wired exactly like
     * boot wires them.
     */
    private class World : AutoCloseable {
        val states = InMemoryViewStateRegistry()
        val packets = PacketStateRegistry(CachedViewStateStore(128, 128))
        val identities = EntityIdentityTracker()
        val disguise = RecordingDisguise()
        val bridge = PacketResyncExecutor(packets, states, disguise, Logger.getLogger("chaos-sync"))
        val sync = PacketStateSync.create(states, bridge, identities)
            ?: error("another test instance still holds the sync claim")

        private var nextEntityId = 1_000

        fun spawn(viewer: UUID, target: UUID): Int {
            val id = nextEntityId++
            packets.bindEntityId(viewer, id, target)
            return id
        }

        fun verdict(viewer: UUID, target: UUID, state: ViewState) = bridge.apply(viewer, target, state, null)

        fun transition(reason: SyncReason, uuid: UUID) = sync.onTransition(reason, uuid)

        /** fresh client: vanilla re-track, then the sync layer re-asserts policy */
        fun reconnect(viewer: UUID) {
            packets.removeViewer(viewer)
            sync.onTransition(SyncReason.RECONNECT, viewer)
        }

        fun identity(uuid: UUID, entityId: Int) = sync.onIdentityRegistered(identities.register(uuid, entityId))

        fun removal(target: UUID) {
            sync.onIdentityDespawned(target)
            packets.removeTarget(target)
        }

        /**
         * the fail-closed contract: whatever the policy row says must already
         * be what the packet layer holds. a HIDDEN row without a HIDDEN applied
         * row is a client that can see the entity.
         */
        fun verify(tag: String) {
            states.allRows().forEach { row ->
                val applied = packets.state(row.viewer, row.target)
                when (row.state) {
                    ViewState.HIDDEN -> assertEquals(
                        ViewState.HIDDEN, applied,
                        "$tag: hidden pair ${row.viewer}->${row.target} has applied state $applied",
                    )
                    ViewState.DISGUISED -> assertEquals(
                        ViewState.DISGUISED, applied,
                        "$tag: disguised pair ${row.viewer}->${row.target} lost its disguise ($applied)",
                    )
                    ViewState.DEAD -> assertTrue(
                        applied != ViewState.DISGUISED,
                        "$tag: dead pair ${row.viewer}->${row.target} still renders against a disguise",
                    )
                    ViewState.VISIBLE -> assertTrue(
                        applied == null || applied == ViewState.VISIBLE,
                        "$tag: vanilla pair ${row.viewer}->${row.target} keeps override $applied",
                    )
                }
            }
        }

        override fun close() {
            sync.release()
        }
    }

    /** the chaos world: one bridge, one coordinator, a recording disguise applier */
    private fun world(): World = World()

    private val allStates = listOf(
        ViewState.HIDDEN, ViewState.HIDDEN, ViewState.HIDDEN,
        ViewState.DISGUISED, ViewState.DISGUISED,
        ViewState.VISIBLE, ViewState.VISIBLE,
        ViewState.DEAD,
    )

    private val allReasons = listOf(
        SyncReason.DEATH, SyncReason.RESPAWN, SyncReason.TELEPORT, SyncReason.RECONNECT,
        SyncReason.WORLD_CHANGE, SyncReason.GAMEMODE_CHANGE, SyncReason.ENTITY_CHANGE,
        SyncReason.EQUIPMENT_CHANGE, SyncReason.QUIT, SyncReason.RECONCILE,
    )

    @Test
    fun `randomised verdict and transition streams never reveal a hidden pair`() {
        world().use { w ->
            val random = Random(0x5EED)
            val entities = HashMap<Pair<UUID, UUID>, Int>()
            repeat(1_500) { step ->
                val viewer = viewerId(random.nextInt(3) + 1)
                val target = targetId(random.nextInt(3) + 1)
                val pair = viewer to target
                when (random.nextInt(100)) {
                    in 0..39 -> w.verdict(viewer, target, allStates[random.nextInt(allStates.size)])
                    in 40..64 -> w.transition(allReasons[random.nextInt(allReasons.size)], if (random.nextBoolean()) viewer else target)
                    in 65..74 -> {
                        // tracking in: the client now holds the entity
                        entities[pair] = w.spawn(viewer, target)
                    }
                    in 75..84 -> {
                        // tracking out: the client dropped it
                        val id = entities.remove(pair) ?: return@repeat
                        w.packets.unbindEntityIds(viewer, intArrayOf(id))
                    }
                    in 85..89 -> w.reconnect(viewer)
                    in 90..94 -> {
                        // the same server entity id is reused by another target
                        val stolen = targetId(random.nextInt(3) + 1)
                        w.identity(stolen, entities[pair] ?: random.nextInt(50))
                    }
                    else -> w.removal(target)
                }
                w.verify("step $step")
            }
        }
    }

    @Test
    fun `a death pin swallows stale settles and only respawn clears it`() {
        val viewer = viewerId(1)
        val target = targetId(1)
        world().use { w ->
            w.spawn(viewer, target)
            w.verdict(viewer, target, ViewState.DISGUISED)
            w.transition(SyncReason.DEATH, target)
            assertEquals(ViewState.DEAD, w.states.get(viewer, target))
            assertEquals(ViewState.VISIBLE, w.packets.state(viewer, target), "a corpse renders vanilla")
            w.disguise.forget()

            val random = Random(11)
            repeat(200) { step ->
                // engine snapshots trail the death event: a settle computed from
                // the old snapshot keeps saying disguised/hidden for a while
                val stale = listOf(ViewState.DISGUISED, ViewState.HIDDEN, ViewState.VISIBLE)
                    .let { it[random.nextInt(it.size)] }
                w.verdict(viewer, target, stale)
                assertEquals(ViewState.DEAD, w.states.get(viewer, target), "stale settle #$step cleared the pin")
                assertEquals(ViewState.VISIBLE, w.packets.state(viewer, target), "stale settle #$step showed a disguise")
                w.verify("stale settle $step")
            }
            assertTrue(w.disguise.calls.isEmpty(), "no disguise work may run for a dead pair: ${w.disguise.calls}")

            w.transition(SyncReason.RESPAWN, target)
            w.verify("after respawn")
            // respawn is the documented restore: the pin is gone and policy wins again
            w.verdict(viewer, target, ViewState.HIDDEN)
            assertEquals(ViewState.HIDDEN, w.packets.state(viewer, target))
        }
    }

    @Test
    fun `teleport and reconnect re-establish the applied picture without a visible window`() {
        val viewer = viewerId(1)
        val target = targetId(1)
        world().use { w ->
            w.verdict(viewer, target, ViewState.HIDDEN)
            w.spawn(viewer, target)

            repeat(100) { step ->
                // cross-world teleport of the target: the client flushes
                // tracking and gets a per-target reset, then the policy reapplies
                w.transition(SyncReason.TELEPORT, target)
                w.verify("teleport $step")

                // the viewer reconnects: a fresh client holds nothing at all
                w.reconnect(viewer)
                w.verify("reconnect $step")
                assertEquals(ViewState.HIDDEN, w.packets.state(viewer, target))

                // world change on the viewer side resets and re-asserts too
                w.transition(SyncReason.WORLD_CHANGE, viewer)
                w.verify("world change $step")
            }
        }
    }

    @Test
    fun `tracking out keeps the override so the retrack spawn stays suppressed`() {
        val viewer = viewerId(1)
        val target = targetId(1)
        world().use { w ->
            val id = w.spawn(viewer, target)
            w.verdict(viewer, target, ViewState.HIDDEN)

            repeat(100) {
                w.packets.unbindEntityIds(viewer, intArrayOf(id))
                // the policy row is what suppresses the next spawn packet
                assertEquals(ViewState.HIDDEN, w.states.get(viewer, target), "the row died on tracking-out")
                w.verify("tracking out")
                // retrack: a fresh entity id for the same target
                val fresh = w.spawn(viewer, target)
                w.verify("retrack")
                w.packets.unbindEntityIds(viewer, intArrayOf(fresh))
            }
        }
    }

    @Test
    fun `a reused entity id cannot drag the previous owner's disguise onto the new entity`() {
        val viewer = viewerId(1)
        val oldOwner = targetId(1)
        val newOwner = targetId(2)
        world().use { w ->
            val reused = 4242
            w.packets.bindEntityId(viewer, reused, oldOwner)
            w.verdict(viewer, oldOwner, ViewState.HIDDEN)
            w.identity(oldOwner, reused)
            w.disguise.forget()

            // the old owner despawns and the server hands its id to a new entity
            w.identity(newOwner, reused)
            // the packet listener rebinds the reused id to the new owner
            w.packets.bindEntityId(viewer, reused, newOwner)

            assertNull(w.identities.current(oldOwner), "the tracker must forget the old owner")
            assertEquals(newOwner, w.identities.resolve(newOwner, reused)?.uuid)
            assertEquals(newOwner, w.packets.targetOf(viewer, reused))
            assertNull(w.states.get(viewer, oldOwner), "stale policy of the evicted owner must be dropped")
            assertNull(w.states.get(viewer, newOwner), "the new owner never got a verdict")
            assertNull(w.packets.state(viewer, newOwner), "the new entity inherits no override")
            assertTrue(
                w.disguise.calls.none { it.contains(newOwner.toString()) },
                "no packet work may fire for the reused id: ${w.disguise.calls}",
            )

            // and the old owner re-tracks cleanly under its own new id
            val fresh = w.spawn(viewer, oldOwner)
            w.verdict(viewer, oldOwner, ViewState.HIDDEN)
            w.packets.unbindEntityIds(viewer, intArrayOf(fresh))
            assertEquals(ViewState.HIDDEN, w.states.get(viewer, oldOwner))
        }
    }

    @Test
    fun `entity removal drops the pair instead of leaving a hidden ghost row`() {
        val viewer = viewerId(1)
        val target = targetId(1)
        world().use { w ->
            w.spawn(viewer, target)
            w.verdict(viewer, target, ViewState.HIDDEN)

            w.removal(target)

            assertNull(w.states.get(viewer, target), "the policy row dies with the entity")
            assertNull(w.packets.state(viewer, target), "the applied row dies with the entity")
            assertTrue(w.identities.current(target) == null)
            w.verify("after removal")

            // the same uuid rejoins later and is decided from scratch
            w.spawn(viewer, target)
            w.verdict(viewer, target, ViewState.HIDDEN)
            assertEquals(ViewState.HIDDEN, w.packets.state(viewer, target))
        }
    }

    @Test
    fun `concurrent verdicts and transitions converge back to the fail-closed picture`() {
        val viewers = (1..2).map { viewerId(it) }
        val targets = (1..2).map { targetId(it) }
        world().use { w ->
            viewers.forEach { v -> targets.forEach { t -> w.verdict(v, t, ViewState.HIDDEN) } }

            val pool = Executors.newFixedThreadPool(6)
            val barrier = CyclicBarrier(6)
            val jobs = mutableListOf<java.util.concurrent.Future<Boolean>>()
            try {
                jobs += (0 until 4).map { worker ->
                    pool.submit(Callable {
                        barrier.await()
                        val random = Random(worker * 31L + 7)
                        val viewer = viewers[worker % viewers.size]
                        val target = targets[worker % targets.size]
                        repeat(300) {
                            w.verdict(viewer, target, allStates[random.nextInt(allStates.size)])
                        }
                        true
                    })
                }
                jobs += pool.submit(Callable {
                    barrier.await()
                    val random = Random(99)
                    repeat(300) {
                        val reason = allReasons[random.nextInt(allReasons.size)]
                        val uuid = if (random.nextBoolean()) viewers[random.nextInt(2)] else targets[random.nextInt(2)]
                        w.transition(reason, uuid)
                    }
                    true
                })
                jobs += pool.submit(Callable {
                    barrier.await()
                    repeat(300) {
                        w.reconnect(viewers[it % 2])
                    }
                    true
                })
                // worker exceptions surface here instead of being swallowed
                jobs.forEach { it.get() }
            } finally {
                pool.shutdown()
            }

            // quiesce: every viewer gets a fresh client re-assert of its policy
            viewers.forEach { w.reconnect(it) }
            w.verify("after the concurrent stream")
        }
    }
}
