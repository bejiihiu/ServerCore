package kz.bejiihiu.servercore.bars

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kz.bejiihiu.servercore.ui.UiScheduler.uiNow
import kz.bejiihiu.servercore.ui.UiScheduler.uiRepeat
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * the bar framework: one registry of what each player shows, one refresh task
 * per player behind it, and the toggle API commands and future phases call.
 *
 * folia rules this class exists to enforce:
 * - a player's bar task runs on that player's entity scheduler, so reading
 *   their location for region tps is legal and sending the packet is legal
 * - the jmx sampler is the metric's own async task; region threads only read
 *   a volatile snapshot
 * - no bukkit scheduler anywhere
 */
class InfoBarService(
    private val plugin: Plugin,
    private val config: BarsConfig,
    private val selections: BarsState,
    private val store: BarsStore,
    metrics: List<BarMetric>,
    private val renderer: BarRenderer,
    private val snapshots: BarSnapshots = BarSnapshots(),
) {

    private val metrics: Map<BarId, BarMetric> = metrics.associateBy { it.bar }
    private val lock = Any()
    private val active = ConcurrentHashMap<UUID, MutableMap<BarSurface, ActiveBar>>()
    private val dirty = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    @Volatile
    private var ambientTask: ScheduledTask? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        metrics.values.forEach { it.start(plugin, config.updateIntervalTicks) }
        startAmbientSampler()
        // players already online (a reload, or a late enable) get their persisted bars back
        plugin.server.onlinePlayers.forEach { player -> player.uiNow(plugin) { reconcile(player) } }
    }

    /**
     * cancels every refresh task, hides what is still on screen and flushes the
     * toggle store. ordering matters: the store flush needs the io pool, which
     * the plugin shuts down after this returns.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        ambientTask?.cancel()
        ambientTask = null
        val live = synchronized(lock) { active.values.flatMap { it.values } }
        live.forEach { it.task?.cancel() }
        synchronized(lock) { active.clear() }
        plugin.server.onlinePlayers.forEach { player -> player.uiNow(plugin) { renderer.clear(player) } }
        if (dirty.get()) store.writeNow(selections.snapshot())
        metrics.values.forEach { it.stop() }
    }

    fun onJoin(player: Player) {
        player.uiNow(plugin) { reconcile(player) }
    }

    fun onQuit(player: Player) {
        cancelAll(player.uniqueId)
        // no player access here: by quit the entity scheduler is going away
        renderer.forget(player.uniqueId)
        snapshots.forget(player.uniqueId)
        metrics.values.forEach { it.forget(player.uniqueId) }
    }

    /** the bar this player currently has on the configured surface */
    fun active(player: Player): BarId? = selections.active(player.uniqueId, config.surface)

    /** turns a bar on; true when the choice changed */
    fun enable(player: Player, bar: BarId): Boolean {
        if (!selections.enable(player.uniqueId, bar, config.surface)) return false
        persist()
        player.uiNow(plugin) { reconcile(player) }
        return true
    }

    /** turns a bar off; true when the choice changed */
    fun disable(player: Player, bar: BarId): Boolean {
        if (!selections.disable(player.uniqueId, bar, config.surface)) return false
        persist()
        player.uiNow(plugin) { reconcile(player) }
        return true
    }

    /** flips a bar; returns the new state */
    fun toggle(player: Player, bar: BarId): Boolean {
        val enabled = selections.toggle(player.uniqueId, bar, config.surface)
        persist()
        player.uiNow(plugin) { reconcile(player) }
        return enabled
    }

    /** group toggle; returns the new state */
    fun toggleAll(players: Collection<Player>, bar: BarId): Boolean {
        if (players.isEmpty()) return false
        val enabled = selections.toggleAll(players.map { it.uniqueId }, bar, config.surface)
        persist()
        players.forEach { player -> player.uiNow(plugin) { reconcile(player) } }
        return enabled
    }

    /**
     * brings one player's task in line with their stored choice. runs on the
     * player's entity scheduler; the active map is only mutated from there.
     */
    private fun reconcile(player: Player) {
        // a queued task can land after disable; it must not resurrect the bar
        if (!running.get()) return
        val surface = config.surface
        val wanted = selections.active(player.uniqueId, surface)
        if (wanted == null || !config.isEnabled(wanted)) {
            // a bar removed from config must not keep rendering from an old choice
            stopSurface(player.uniqueId, surface)
            renderer.clear(player)
            return
        }

        val current = synchronized(lock) { active[player.uniqueId]?.get(surface) }
        if (current != null && current.bar == wanted) return

        stopSurface(player.uniqueId, surface)
        val metric = metrics[wanted]
        if (metric == null) {
            plugin.logger.warning("info bar ${wanted.key} has no metric, nothing to render")
            return
        }

        render(player, wanted, metric)
        val task = player.uiRepeat(plugin, config.updateIntervalTicks, config.updateIntervalTicks) {
            render(player, wanted, metric)
        }
        synchronized(lock) {
            active.getOrPut(player.uniqueId) { HashMap() }[surface] = ActiveBar(wanted, task)
        }
    }

    private fun render(player: Player, bar: BarId, metric: BarMetric) {
        try {
            val reading = metric.read(player)
            renderer.render(player, bar, reading, config.surface)
            // the same reading feeds placeholderapi; player-scoped, because a
            // region tps belongs to where this player stands
            snapshots.publish(bar, reading, player.uniqueId)
        } catch (e: Exception) {
            // one bad tick must not kill the whole bar loop
            plugin.logger.warning("info bar ${bar.key} failed for ${player.name}: ${e.message}")
        }
    }

    /**
     * player-independent readings on a fixed cadence, on the global region
     * scheduler. these metrics read no player and no world, so this is legal
     * from that thread, and it keeps placeholderapi honest with zero bars
     * toggled — an admin's scoreboard should not depend on somebody wearing
     * the bar.
     */
    private fun startAmbientSampler() {
        sampleAmbient()
        ambientTask = plugin.server.globalRegionScheduler.runAtFixedRate(
            plugin,
            { sampleAmbient() },
            1L,
            config.updateIntervalTicks,
        )
    }

    private fun sampleAmbient() {
        for ((bar, metric) in metrics) {
            val reading = runCatching { metric.readGlobal() }
                .onFailure { plugin.logger.warning("info bar ${bar.key} global sample failed: ${it.message}") }
                .getOrNull() ?: continue
            snapshots.publish(bar, reading)
        }
    }

    private fun stopSurface(uuid: UUID, surface: BarSurface) {
        val removed = synchronized(lock) {
            val bySurface = active[uuid] ?: return
            val bar = bySurface.remove(surface)
            if (bySurface.isEmpty()) active.remove(uuid)
            bar
        }
        removed?.task?.cancel()
    }

    private fun cancelAll(uuid: UUID) {
        val bySurface = synchronized(lock) { active.remove(uuid) } ?: return
        bySurface.values.forEach { it.task?.cancel() }
    }

    private fun persist() {
        dirty.set(true)
        store.write(selections.snapshot())
    }

    private class ActiveBar(val bar: BarId, val task: ScheduledTask?)
}
