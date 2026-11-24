package kz.bejiihiu.servercore.esp

import java.util.Random
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * orchestrates the whole anti-esp pipeline:
 *
 * - every tracked player captures its own [PlayerSnapshot] on its entity
 *   scheduler (folia-correct, zero global-thread player reads)
 * - snapshots feed a chunk-coarse [SpatialGrid], incrementally updated
 * - each engine cycle (global region scheduler, every check-interval ticks)
 *   pulls candidate pairs from grid buckets and refreshes at most a fixed
 *   budget of stale pairs via the round-robin cursor - never all pairs
 * - pairs whose verdict needs geometry get a region-safe raytrace; the
 *   [VisibilityCache] TTL and the pending flag keep raytraces rare
 * - verdict transitions go through [ViewStateApplier]; the real packet writer
 *   registers itself via [attachApplier], the no-op keeps this side standalone
 *
 * pair semantics are ordered: (a views b) and (b views a) are independent
 * decisions, disguise is personal per viewer.
 */
class VisibilityEngine(
    private val plugin: Plugin,
    private val config: EspConfig,
    private val rules: EspRules,
    private val guestLookup: (UUID) -> Boolean = { false },
    private val specLookup: (UUID) -> Boolean = { false },
) {

    @Volatile
    var applier: ViewStateApplier = NoopViewStateApplier

    private val decider = PairVisibilityDecider(rules)
    private val grid = SpatialGrid()
    private val cache = VisibilityCache(ttlCycles())
    private val raytracer = RegionSafeRaytracer(plugin)
    private val snapshots = ConcurrentHashMap<UUID, PlayerSnapshot>()
    private val playerTasks = ConcurrentHashMap<UUID, io.papermc.paper.threadedregions.scheduler.ScheduledTask>()

    private val cycle = AtomicLong(0)
    private val cursor = AtomicLong(0)
    private val rng = Random()
    private var engineTask: io.papermc.paper.threadedregions.scheduler.ScheduledTask? = null

    /** chunks of bucket lookup around a viewer, +1 margin for chunk-edge cases */
    private val chunkRadius = ceil(sqrt(rules.maxDistanceSquared) / CHUNK_BLOCKS).toInt() + 1

    // ---- lifecycle ----

    fun start() {
        engineTask = plugin.server.globalRegionScheduler.runAtFixedRate(
            plugin,
            { _ -> tick() },
            config.checkIntervalTicks,
            config.checkIntervalTicks,
        )
    }

    fun stop() {
        engineTask?.cancel()
        engineTask = null
        playerTasks.values.forEach { it.cancel() }
        playerTasks.clear()
        snapshots.clear()
        grid.clear()
        cache.clear()
    }

    /** swaps the no-op out once the packet layer is up */
    fun attachApplier(real: ViewStateApplier) {
        applier = real
    }

    // ---- player tracking (called from join/quit events, region threads) ----

    fun track(player: Player) {
        val period = config.checkIntervalTicks
        val task = player.scheduler.runAtFixedRate(
            plugin,
            { _ -> capture(player) },
            null,
            period,
            period,
        )
        if (task != null) playerTasks[player.uniqueId] = task
    }

    fun untrack(uuid: UUID) {
        playerTasks.remove(uuid)?.cancel()
        snapshots.remove(uuid)
        grid.remove(uuid)
        cache.removePlayer(uuid)
    }

    /** runs on the player's entity scheduler, the only legit thread for this */
    private fun capture(player: Player) {
        val snap = PlayerSnapshot.capture(player)
        snapshots[snap.id] = snap
        grid.update(
            snap.id,
            SpatialGrid.Cell(snap.world, chunkCoord(snap.feet.x), chunkCoord(snap.feet.z)),
        )
    }

    // ---- engine cycle ----

    private fun tick() {
        val now = cycle.incrementAndGet()
        val tracked = snapshots.values.toList()
        if (tracked.size < 2) return

        val pairs = candidatePairs(tracked)
        if (pairs.isEmpty()) return

        // bounded refresh: stale pairs drip through the round-robin cursor,
        // a cycle never bills more than [budget] pair evaluations
        val budget = maxOf(MIN_BUDGET, 2 * tracked.size)
        var index = (cursor.getAndIncrement() % pairs.size).toInt()
        repeat(minOf(budget, pairs.size)) {
            val key = pairs[index]
            index = (index + 1) % pairs.size
            val viewer = snapshots[key.viewer] ?: return@repeat
            val target = snapshots[key.target] ?: return@repeat
            refreshPair(viewer, target, now)
        }
    }

    private fun candidatePairs(tracked: List<PlayerSnapshot>): List<PairKey> {
        val pairs = ArrayList<PairKey>()
        for (viewer in tracked) {
            val cell = grid.cellOf(viewer.id) ?: continue
            for (targetId in grid.nearby(cell, chunkRadius, viewer.id)) {
                if (snapshots.containsKey(targetId)) pairs += PairKey(viewer.id, targetId)
            }
        }
        // stable order so the round-robin cursor treats pairs fairly
        pairs.sortWith(compareBy({ it.viewer }, { it.target }))
        return pairs
    }

    private fun refreshPair(viewer: PlayerSnapshot, target: PlayerSnapshot, now: Long) {
        val key = PairKey(viewer.id, target.id)
        val current = cache[key]
        if (current != null && (cache.fresh(current, now) || current.losPending)) return

        val facts = factsOf(viewer, target)
        if (decider.needsLineOfSight(facts)) {
            // park as pending so concurrent cycles don't double-trace; the
            // previous verdict (or a fail-closed HIDDEN) keeps rendering until
            // the trace lands
            cache.upsert(key) { e ->
                if (e != null && e.losPending) {
                    e
                } else {
                    VisibilityCache.Entry(
                        verdict = e?.verdict ?: PairVerdict(ViewState.HIDDEN),
                        updatedAt = e?.updatedAt ?: now,
                        losBlocked = null,
                        losPending = true,
                    )
                }
            }
            launchTrace(viewer, target, key, facts)
        } else {
            settle(key, facts.copy(losBlocked = false), now)
        }
    }

    private fun factsOf(viewer: PlayerSnapshot, target: PlayerSnapshot) = PairFacts(
        sameWorld = viewer.world == target.world,
        distanceSquared = viewer.eye.distanceSquaredTo(target.eye),
        losBlocked = null,
        targetSneaking = target.sneaking,
        viewerSpectator = viewer.spectator,
        targetSpectator = target.spectator,
        viewerDead = viewer.dead,
        targetDead = target.dead,
        bothGuests = guestLookup(viewer.id) && guestLookup(target.id),
        targetSpecObserving = specLookup(target.id),
    )

    private fun launchTrace(
        viewer: PlayerSnapshot,
        target: PlayerSnapshot,
        key: PairKey,
        facts: PairFacts,
    ) {
        val world = plugin.server.getWorld(viewer.world)
        if (world == null) {
            // world went away mid-flight: fail closed rather than leak
            settle(key, facts.copy(losBlocked = true), cycle.get())
            return
        }
        // two probe rays: eye-to-eye plus eye-to-torso as a fallback for
        // half-cover; either one clearing means the target can be seen
        val rays = listOf(
            Ray(viewer.eye, target.eye),
            Ray(viewer.eye, Vec3(target.feet.x, target.feet.y + TORSO_OFFSET, target.feet.z)),
        )
        val futures = rays.map { raytracer.traceBlocked(world, it, viewer.minY, viewer.maxY) }
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ ->
            // hidden only when every probe ray is blocked; a failed future
            // reports blocked (getOrDefault true), keeping the fail-closed pose
            val blocked = futures.all { runCatching { it.join() }.getOrDefault(true) }
            settle(key, facts.copy(losBlocked = blocked), cycle.get())
        }
    }

    private fun settle(key: PairKey, facts: PairFacts, now: Long) {
        val previous = cache[key]?.verdict
        val verdict = decider.decide(facts, previous, rng)
        cache.upsert(key) { e ->
            // a settle from a later cycle already landed, don't clobber it
            if (e != null && e.updatedAt > now && !e.losPending) {
                e
            } else {
                VisibilityCache.Entry(verdict, now, facts.losBlocked, losPending = false)
            }
        }
        // every settle applies, not only verdict changes: appliers are
        // idempotent by contract, and a re-tracked client or an evicted
        // packet row heals itself on the next settle instead of leaking
        applier.apply(key.viewer, key.target, verdict.state, verdict.disguise)
    }

    private fun ttlCycles(): Long = maxOf(1L, ceil(config.cacheTtlTicks.toDouble() / config.checkIntervalTicks).toLong())

    private fun chunkCoord(value: Double): Int = floor(value / CHUNK_BLOCKS).toInt()

    private companion object {
        const val CHUNK_BLOCKS = 16.0
        const val MIN_BUDGET = 16
        const val TORSO_OFFSET = 0.9
    }
}
