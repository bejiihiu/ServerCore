package kz.bejiihiu.servercore.bench

import java.nio.file.Files
import java.util.Locale
import java.util.Random
import java.util.UUID
import java.util.logging.Logger
import kotlin.math.cos
import kotlin.math.sin
import kz.bejiihiu.servercore.bars.BarFormats
import kz.bejiihiu.servercore.bars.BarHealth
import kz.bejiihiu.servercore.bars.BarId
import kz.bejiihiu.servercore.bars.BarPresentation
import kz.bejiihiu.servercore.bars.BarReading
import kz.bejiihiu.servercore.bars.BarSurface
import kz.bejiihiu.servercore.bars.BarTitleCache
import kz.bejiihiu.servercore.esp.DisguiseAssignment
import kz.bejiihiu.servercore.esp.EspRules
import kz.bejiihiu.servercore.esp.PairFacts
import kz.bejiihiu.servercore.esp.PairVerdict
import kz.bejiihiu.servercore.esp.PairVisibilityDecider
import kz.bejiihiu.servercore.esp.Ray
import kz.bejiihiu.servercore.esp.RayMarch
import kz.bejiihiu.servercore.esp.SpatialGrid
import kz.bejiihiu.servercore.esp.Vec3
import kz.bejiihiu.servercore.esp.ViewState
import kz.bejiihiu.servercore.esp.VoxelPos
import kz.bejiihiu.servercore.locale.LocaleService
import kz.bejiihiu.servercore.packet.CachedViewStateStore
import kz.bejiihiu.servercore.packet.EntityViewState
import kz.bejiihiu.servercore.packet.PacketStateRegistry
import kz.bejiihiu.servercore.sync.InMemoryViewStateRegistry
import kz.bejiihiu.servercore.sync.PacketResyncExecutor
import kz.bejiihiu.servercore.ui.Theme
import net.kyori.adventure.text.Component

/**
 * benchmarks for the packet/esp/sync/bars hot paths. nothing here touches a
 * server: every layer measured has a pure core, and the shapes (ray lengths,
 * player counts, pair counts) mirror what the engine actually produces.
 */
object HotPathBenchmarks {

    fun run() {
        raymarch()
        spatialGrid()
        decider()
        packetRegistry()
        cachedStore()
        resyncExecutor()
        bars()
    }

    // ---- esp: ray march ----

    private fun raymarch() {
        Harness.section("esp / raymarch")

        for (length in intArrayOf(10, 30, 50)) {
            val rays = rays(64, length.toDouble())
            val averageVoxels = rays.indices.sumOf { RayMarch.voxels(rays[it].start, rays[it].end).size } / rays.size
            var index = 0
            Harness.bench(
                "dda ${length}b",
                note = "~$averageVoxels voxels/ray",
            ) {
                val ray = rays[index++ % rays.size]
                RayMarch.voxels(ray.start, ray.end)
            }
        }

        // what the region task does after the dda: walk the list, first opaque
        // block wins. the dda cost is identical, the scan cost is what changes
        val rays30 = rays(64, 30.0)
        val voxels30 = rays30.map { RayMarch.voxels(it.start, it.end) }
        var voxelIndex = 0
        Harness.bench("scan open 30b", note = "no occluder, walks the whole list") {
            scanBlocked(voxels30[voxelIndex++ % voxels30.size]) { _, _, _ -> false }
        }
        var treeIndex = 0
        Harness.bench("scan tree line 30b", note = "~1 in 7 voxels solid") {
            scanBlocked(voxels30[treeIndex++ % voxels30.size]) { x, y, z -> isTreeSolid(x, y, z) }
        }
        var wallIndex = 0
        Harness.bench("scan wall@8 30b", note = "early break after 8 blocks") {
            scanBlocked(voxels30[wallIndex++ % voxels30.size]) { x, _, _ -> x >= 8 }
        }

        // the whole pure half of one raytrace: traversal + vertical clamp +
        // chunk grouping (the bukkit block reads happen later on the region)
        var prepIndex = 0
        Harness.bench("trace prep 30b", note = "voxels + y-clamp + groupBy chunk") {
            val ray = rays30[prepIndex++ % rays30.size]
            RayMarch.voxels(ray.start, ray.end)
                .filter { it.y in 0..255 }
                .groupBy { SpatialGrid.Cell("world", it.chunkX, it.chunkZ) }
        }
    }

    private fun scanBlocked(voxels: List<VoxelPos>, solid: (Int, Int, Int) -> Boolean): Boolean {
        for (voxel in voxels) if (solid(voxel.x, voxel.y, voxel.z)) return true
        return false
    }

    private fun isTreeSolid(x: Int, y: Int, z: Int): Boolean {
        val mixed = (x * 73856093) xor (y * 19349663) xor (z * 83492791)
        return mixed % 7 == 0
    }

    /** varied directions, so every ray is a slightly different dda path */
    private fun rays(count: Int, length: Double): Array<Ray> {
        val rng = Random(0x5EED_0000L + length.toLong())
        return Array(count) {
            val yaw = rng.nextDouble() * Math.PI * 2
            val pitch = (rng.nextDouble() - 0.5) * Math.PI * 0.9
            val dx = cos(yaw) * cos(pitch) * length
            val dy = sin(pitch) * length
            val dz = sin(yaw) * cos(pitch) * length
            Ray(Vec3(0.5, 64.5, 0.5), Vec3(0.5 + dx, 64.5 + dy, 0.5 + dz))
        }
    }

    // ---- esp: spatial grid ----

    private fun spatialGrid() {
        Harness.section("esp / spatial grid (one engine cycle, radius 4 cells)")

        for (players in intArrayOf(100, 500, 2000)) {
            val grid = SpatialGrid()
            val ids = Array(players) { uuid(it) }
            val cells = Array(players) { cellOf(it) }
            for (i in 0 until players) grid.update(ids[i], cells[i])

            var candidates = 0L
            for (i in 0 until players) {
                candidates += grid.nearby(cells[i], RADIUS, ids[i]).size
            }
            val average = candidates.toDouble() / players

            var index = 0
            Harness.bench("nearby ${players}p", note = "${"%.0f".format(Locale.ROOT, average)} candidates/query") {
                val i = index++ % players
                grid.nearby(cells[i], RADIUS, ids[i])
            }
        }

        // rebuild cost per snapshot: standing still is a map put, moving is
        // a bucket remove + add
        val grid = SpatialGrid()
        val ids = Array(500) { uuid(it) }
        for (i in ids.indices) grid.update(ids[i], cellOf(i))
        var stationaryIndex = 0
        Harness.bench("update unchanged 500p") {
            val i = stationaryIndex++ % ids.size
            grid.update(ids[i], cellOf(i))
        }
        var movingIndex = 0
        Harness.bench("update moved 500p", note = "remove + add across buckets") {
            val i = movingIndex++ % ids.size
            grid.update(ids[i], SpatialGrid.Cell(WORLD, (i * 37 % 500) shr 4, ((i * 91 % 500) + movingIndex) shr 4))
        }
    }

    private fun cellOf(index: Int): SpatialGrid.Cell =
        SpatialGrid.Cell(WORLD, (index * 37 % 500) shr 4, (index * 91 % 500) shr 4)

    // ---- esp: decision matrix ----

    private fun decider() {
        Harness.section("esp / pair visibility decider")

        val rules = EspRules(
            maxDistanceSquared = 48.0 * 48.0,
            sneakHides = true,
            disguiseEnabled = true,
            mobs = listOf("ZOMBIE", "SKELETON", "SPIDER", "COW"),
        )
        val decider = PairVisibilityDecider(rules)
        val noDisguise = PairVisibilityDecider(rules.copy(disguiseEnabled = false))
        val rng = Random(42)
        val disguised = PairVerdict(ViewState.DISGUISED, DisguiseAssignment("SKELETON"))

        // every row is a shape the engine really sees; the mix matters more
        // than any single one
        val matrix = listOf(
            facts(distance = 10.0, los = false), // clear path
            facts(distance = 10.0, los = true), // blocked, disguise rolls
            facts(distance = 10.0, los = null), // trace in flight, no verdict yet
            facts(distance = 10.0, los = true), // blocked, disguise already assigned
            facts(distance = 10.0, los = null), // pending with a previous verdict
            facts(distance = 10.0, sneaking = true), // sneak hide, no geometry
            facts(distance = 80.0, los = null), // beyond the managed radius
            facts(distance = 10.0, viewerSpectator = true),
            facts(distance = 10.0, targetDead = true),
            facts(distance = 10.0, bothGuests = true),
            facts(distance = 10.0, specObserving = true),
            facts(distance = 10.0, sameWorld = false),
        )
        val previous = arrayOf(null, disguised, PairVerdict(ViewState.HIDDEN), PairVerdict(ViewState.VISIBLE))

        var index = 0
        Harness.bench("decide matrix (12 shapes)") {
            val i = index++ % matrix.size
            decider.decide(matrix[i], previous[i % previous.size], rng)
        }
        var cheapIndex = 0
        Harness.bench("decide disguise off (12 shapes)") {
            val i = cheapIndex++ % matrix.size
            noDisguise.decide(matrix[i], previous[i % previous.size], rng)
        }
        var gateIndex = 0
        Harness.bench("needsLineOfSight (12 shapes)", note = "same gate chain, no verdict") {
            decider.needsLineOfSight(matrix[gateIndex++ % matrix.size])
        }
    }

    private fun facts(
        sameWorld: Boolean = true,
        distance: Double = 10.0,
        los: Boolean? = null,
        sneaking: Boolean = false,
        viewerSpectator: Boolean = false,
        targetSpectator: Boolean = false,
        viewerDead: Boolean = false,
        targetDead: Boolean = false,
        bothGuests: Boolean = false,
        specObserving: Boolean = false,
    ) = PairFacts(
        sameWorld = sameWorld,
        distanceSquared = distance * distance,
        losBlocked = los,
        targetSneaking = sneaking,
        viewerSpectator = viewerSpectator,
        targetSpectator = targetSpectator,
        viewerDead = viewerDead,
        targetDead = targetDead,
        bothGuests = bothGuests,
        targetSpecObserving = specObserving,
    )

    // ---- packet: registry ----

    private fun packetRegistry() {
        Harness.section("packet / state registry (200 viewers x 200 targets)")

        val viewers = Array(200) { uuid(it) }
        val targets = Array(200) { targetUuid(it) }
        val unknownViewers = Array(64) { uuid(1_000_000 + it) }
        val registry = PacketStateRegistry(CachedViewStateStore(maxViewers = 1024, maxTargetsPerViewer = 512))
        for (viewer in 0 until 200) {
            for (target in 0 until 200) {
                registry.bindEntityId(viewers[viewer], target + 1, targets[target])
                registry.set(viewers[viewer], targets[target], if (target % 3 == 0) EntityViewState.HIDDEN else EntityViewState.DISGUISED)
            }
        }

        var hitIndex = 0
        Harness.bench("targetOf hit", note = "1 outer + 1 inner chm lookup") {
            val i = hitIndex++
            registry.targetOf(viewers[i % 200], (i / 200) % 200 + 1)
        }
        var stateIndex = 0
        Harness.bench("state hit") {
            val i = stateIndex++
            registry.state(viewers[i % 200], targets[(i / 200) % 200])
        }
        var missIndex = 0
        Harness.bench("targetOf miss (unknown viewer)") {
            registry.targetOf(unknownViewers[missIndex++ % unknownViewers.size], 1)
        }
        var setIndex = 0
        Harness.bench("set override") {
            val i = setIndex++
            registry.set(viewers[i % 200], targets[(i / 200) % 200], EntityViewState.HIDDEN)
        }

        // bind + unbind is one spawn/destroy lifecycle; the store part of the
        // unbind is what the registry pays for a destroy packet
        val churnViewer = uuid(900_000)
        var churn = 0
        Harness.bench("bind + unbind cycle", note = "spawn then destroy for one entity id") {
            val id = 100_000 + (churn % 256)
            registry.bindEntityId(churnViewer, id, targets[churn % 200])
            churn++
            registry.unbindEntityIds(churnViewer, intArrayOf(id))
        }
    }

    // ---- packet: cached store ----

    private fun cachedStore() {
        Harness.section("packet / cached view state store")

        val now = { 1_700_000_000_000L }
        val viewers = Array(100) { uuid(it) }
        val targets = Array(64) { targetUuid(it) }
        val store = CachedViewStateStore(maxViewers = 1024, maxTargetsPerViewer = 64, now = now)
        val spares = Array(4096) { targetUuid(100_000 + it) }
        for (viewer in 0 until 100) {
            for (target in 0 until 64) store.set(viewers[viewer], targets[target], EntityViewState.HIDDEN)
        }

        var hitIndex = 0
        Harness.bench("get hit") {
            val i = hitIndex++
            store.get(viewers[i % 100], targets[(i / 100) % 64])
        }
        var missIndex = 0
        Harness.bench("get miss") {
            store.get(viewers[missIndex++ % 100], targets[0])
        }
        var overwriteIndex = 0
        Harness.bench("set existing pair", note = "64-target cap, no eviction owed") {
            val i = overwriteIndex++
            store.set(viewers[i % 100], targets[(i / 100) % 64], EntityViewState.HIDDEN)
        }
        var evictIndex = 0
        Harness.bench("set new pair @64 cap", note = "scans 64 entries for the stalest") {
            val i = evictIndex++
            store.set(viewers[i % 100], spares[i % spares.size], EntityViewState.HIDDEN)
        }

        // production cap is 512 targets per viewer - same path, bigger scan
        val wideTargets = Array(512) { targetUuid(it) }
        val wide = CachedViewStateStore(maxViewers = 1024, maxTargetsPerViewer = 512, now = now)
        for (viewer in 0 until 100) {
            for (target in 0 until 512) wide.set(viewers[viewer], wideTargets[target], EntityViewState.HIDDEN)
        }
        var wideIndex = 0
        Harness.bench("set new pair @512 cap", note = "scans 512 entries for the stalest") {
            val i = wideIndex++
            wide.set(viewers[i % 100], spares[i % spares.size], EntityViewState.HIDDEN)
        }
    }

    // ---- sync: resync executor ----

    private fun resyncExecutor() {
        Harness.section("sync / packet resync executor")

        val policy = InMemoryViewStateRegistry()
        val packets = PacketStateRegistry(CachedViewStateStore(maxViewers = 1024, maxTargetsPerViewer = 512))
        val executor = PacketResyncExecutor(packets, policy, disguise = null, logger = null)
        val viewers = 100
        val targets = 100
        val viewerIds = Array(viewers) { uuid(it) }
        val targetIds = Array(targets) { targetUuid(it) }
        for (viewer in 0 until viewers) {
            for (target in 0 until targets) policy.set(viewerIds[viewer], targetIds[target], ViewState.HIDDEN)
        }

        var hiddenIndex = 0
        Harness.bench("apply HIDDEN") {
            val i = hiddenIndex++
            executor.apply(viewerIds[i % viewers], targetIds[(i / viewers) % targets], ViewState.HIDDEN, null)
        }
        var visibleIndex = 0
        Harness.bench("apply VISIBLE", note = "clears the policy row, registry row stays") {
            val i = visibleIndex++
            executor.apply(viewerIds[i % viewers], targetIds[(i / viewers) % targets], ViewState.VISIBLE, null)
        }
        var disguisedIndex = 0
        Harness.bench("apply DISGUISED (no disguise layer)", note = "degrades to HIDDEN") {
            val i = disguisedIndex++
            executor.apply(viewerIds[i % viewers], targetIds[(i / viewers) % targets], ViewState.DISGUISED, null)
        }

        // a policy row for every pair, so resyncTarget always has work to do
        for (viewer in 0 until viewers) {
            for (target in 0 until targets) policy.set(viewerIds[viewer], targetIds[target], ViewState.DISGUISED)
        }
        var resyncIndex = 0
        Harness.bench("resyncTarget", note = "policy get + execute") {
            val i = resyncIndex++
            executor.resyncTarget(viewerIds[i % viewers], targetIds[(i / viewers) % targets])
        }
        var viewerIndex = 0
        Harness.bench("resetViewer (100 targets)", note = "re-asserts every policy row") {
            executor.resetViewer(viewerIds[viewerIndex++ % viewers])
        }
    }

    // ---- bars ----

    private fun bars() {
        Harness.section("bars / formatting and template render")

        val template = "<primary>RAM</primary> <secondary>{used} GB / {max} GB</secondary> <muted>({pct}%)</muted>"
        val values = mapOf("used" to "3.4", "max" to "8.0", "pct" to "42")
        val reading = BarReading(
            fraction = 0.42,
            health = BarHealth.GOOD,
            values = values,
        )
        // a second reading with a different number, so the cached path is measured
        // doing real work instead of returning the same tree for one fixed value set
        val shifted = reading.copy(
            fraction = 0.51,
            values = mapOf("used" to "4.1", "max" to "8.0", "pct" to "51"),
        )
        val readings = arrayOf(reading, shifted)
        val locale = LocaleService(Files.createTempDirectory("servercore-bench-locale"), Logger.getLogger("bench"))
        val titles = BarTitleCache(locale)

        Harness.bench("formats gigabytes") { BarFormats.gigabytes(3_600_000_000L) }
        Harness.bench("formats percent") { BarFormats.percent(0.42) }
        Harness.bench("formats tps") { BarFormats.tps(19.98) }
        Harness.bench("formats clock") { BarFormats.clock(6_000L) }
        Harness.bench("theme render (minimessage)", note = "regex substitute + tag parse") { Theme.render(template, values) }
        var legacyIndex = 0
        Harness.bench("presentation line (parse per tick)", note = "pre-cache path, kept for the before/after row") {
            val r = readings[legacyIndex++ % readings.size]
            uncachedLine(locale, BarId.RAM, r, BarSurface.BOSSBAR)
        }
        var cachedIndex = 0
        Harness.bench("presentation line (cached template)", note = "template parsed once, values dropped in") {
            val r = readings[cachedIndex++ % readings.size]
            BarPresentation.line(titles, BarId.RAM, r, BarSurface.BOSSBAR)
        }
        Harness.bench("locale text (raw string)") { locale.text("bars.ram.title", values) }
    }

    /**
     * the line as it was built before the template cache: locale lookup, regex
     * substitution and a minimessage parse for every render. kept here so the
     * before/after row of `docs/performance.md` can be re-measured on one machine.
     */
    private fun uncachedLine(locale: LocaleService, bar: BarId, reading: BarReading, surface: BarSurface): Component =
        Component.text(
            "${Theme.MARK} ",
            if (surface == BarSurface.BOSSBAR) Theme.GOLD else BarPresentation.markColor(reading.health),
        ).append(locale.component(reading.titleKey ?: bar.titleKey, reading.values))

    // ---- helpers ----

    /** deterministic, well-spread uuids - sequential longs would collide in chm buckets */
    fun uuid(index: Int): UUID = UUID(
        index.toLong() * 0x9E3779B97F4A7C15uL.toLong(),
        index.toLong() * 0xC2B2AE3D27D4EB4FuL.toLong() xor 0x2545F4914F6CDD1DL,
    )

    private fun targetUuid(index: Int): UUID = uuid(1_000_000 + index)

    private const val WORLD = "world"
    private const val RADIUS = 4
}
