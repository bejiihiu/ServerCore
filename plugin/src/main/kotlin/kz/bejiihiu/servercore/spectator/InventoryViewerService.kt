package kz.bejiihiu.servercore.spectator

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItem
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kz.bejiihiu.servercore.util.I18n
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.inventory.ItemStack

/**
 * live read-only inventory view for spec sessions.
 *
 * THE ADMIN'S PHYSICAL INVENTORY IS SACRED: nothing in this service ever
 * writes into the admin's player inventory, never swaps it with the target's,
 * never copies items in or out. the whole feature lives at the packet and
 * container level: the admin opens an empty 6-row backing chest, and an
 * outgoing-packet listener redraws its top 54 slots from a snapshot of the
 * target's inventory. the admin is a spectator of a picture, not a holder of
 * the target's items. if you are about to add `admin.inventory.setItem(...)`
 * or anything similar here — stop, that is exactly the failure mode this
 * service exists to avoid.
 *
 * how it fits together:
 * - [ViewerLayout] maps the target's inventory pieces onto the window slots
 *   (display index == backing slot index, so rewrites are a plain lookup)
 * - [ViewerPacketListener] rewrites outgoing WINDOW_ITEMS / SET_SLOT packets
 *   for that window, which also masks the server's own resync packets after
 *   cancelled clicks — the client never sees the empty truth behind the picture
 * - clicks/drags/creative actions inside the window are cancelled at LOWEST
 *   ([ReadOnlyPolicy]); the server-side backing container stays empty and no
 *   item transfer is possible in either direction
 * - target-side changes (own clicks, pickups, drops, held slot, hopper moves)
 *   just set a dirty flag; the flush loop reads the target's inventory on the
 *   target's own entity scheduler, diffs against the last push and sends only
 *   the changed slots, throttled by config
 * - full resyncs (viewer open, target world change, slow safety backstop)
 *   bypass the throttle and rebuild the whole window packet
 * - a keep-alive check asks the spec session (via [SpecSessionReader]) whether
 *   it still points at the same target; when the session ends, the target
 *   quits or the admin quits, the viewer closes and all temp state is dropped
 *
 * threading follows the folia rules: inventory reads happen on the owning
 * player's entity scheduler, packet sends go through packetevents (thread-safe
 * netty write), shared state is volatile/atomic only.
 */
class InventoryViewerService(
    private val plugin: JavaPlugin,
    private val config: SpecConfig,
    private val i18n: I18n,
) : Listener {

    /** admin uuid -> active viewer; an admin holds at most one viewer */
    private val states = ConcurrentHashMap<UUID, ViewerState>()

    private var packetListener: PacketListenerCommon? = null
    private var flushTask: ScheduledTask? = null
    private var tickCount = 0L

    // === lifecycle ===========================================================

    fun start() {
        if (!config.enabled || !config.liveInventory) {
            plugin.logger.info("live inventory viewer disabled by config")
            return
        }
        plugin.server.pluginManager.registerEvents(this, plugin)
        packetListener = PacketEvents.getAPI().eventManager.registerListener(ViewerPacketListener(this))
        flushTask = plugin.server.globalRegionScheduler.runAtFixedRate(
            plugin,
            { _ -> tick() },
            FLUSH_DELAY_TICKS,
            FLUSH_PERIOD_TICKS,
        )
    }

    fun shutdown() {
        flushTask?.cancel()
        packetListener?.let { PacketEvents.getAPI().eventManager.unregisterListener(it) }
        for (state in states.values.toList()) close(state.adminId, "plugin disabled")
    }

    /**
     * opens a live read-only view of [target]'s inventory for [admin].
     * [session] is the spec session this view is attached to; its keep-alive
     * decides when the view dies. any previous viewer for this admin ends first.
     */
    fun open(admin: Player, target: Player, session: SpecSessionReader) {
        if (!config.enabled || !config.liveInventory) {
            plugin.logger.warning("live inventory view requested but the spec viewer is off in config")
            return
        }
        close(admin.uniqueId, "reopened")

        val state = ViewerState(
            adminId = admin.uniqueId,
            targetId = target.uniqueId,
            session = session,
            throttle = PushThrottle(config.liveInventoryPushMs) { System.currentTimeMillis() },
        )
        states[admin.uniqueId] = state

        // snapshot first, then open the window: the server's own (empty)
        // window-items packet for the fresh container gets rewritten with the
        // ready snapshot, so the admin never even sees an empty frame
        target.scheduler.run(plugin, { t ->
            try {
                if (state.closed) return@run
                state.display = captureTargetDisplay(target)
                admin.scheduler.run(plugin, { a ->
                    try {
                        if (state.closed) return@run
                        val holder = ViewerHolder(admin.uniqueId, target.uniqueId)
                        val inventory = plugin.server.createInventory(
                            holder,
                            ViewerLayout.SIZE,
                            title(target),
                        )
                        holder.attach(inventory)
                        admin.openInventory(inventory)
                        // the outgoing open-window packet tells the packet listener
                        // which window id to rewrite; after that a full push syncs
                        // everything including the captured state id
                        state.needsFull.set(true)
                    } finally {
                        a.cancel()
                    }
                }, null)
            } finally {
                t.cancel()
            }
        }, null)
    }

    /** ends the admin's viewer, if any; idempotent, safe from any thread */
    fun close(adminId: UUID, reason: String) {
        val state = states.remove(adminId) ?: return
        end(state, reason, closeWindow = true)
    }

    /** ends the viewer without touching the window (the admin already closed it) */
    private fun endViaCloseEvent(state: ViewerState) {
        states.remove(state.adminId)
        end(state, "admin closed the view", closeWindow = false)
    }

    private fun end(state: ViewerState, reason: String, closeWindow: Boolean) {
        state.closed = true
        plugin.logger.info("inventory viewer for ${state.adminId} (target ${state.targetId}) ended: $reason")
        if (!closeWindow) return
        val admin = plugin.server.getPlayer(state.adminId) ?: return
        runCatching {
            admin.scheduler.run(plugin, { task ->
                try {
                    // close only if our window is still on top; a foreign view
                    // that replaced ours is none of our business
                    if (admin.openInventory.topInventory.holder is ViewerHolder) admin.closeInventory()
                } finally {
                    task.cancel()
                }
            }, null)
        }.onFailure { plugin.logger.warning("could not schedule viewer close: ${it.message}") }
    }

    /** active viewer state for [adminId], if any (also used by the packet listener) */
    fun stateFor(adminId: UUID): ViewerState? = states[adminId]

    // === flush loop (global region scheduler) ================================

    private fun tick() {
        tickCount++
        val now = System.currentTimeMillis()
        for (state in states.values.toList()) {
            if (state.closed) {
                states.remove(state.adminId)
                continue
            }
            val admin = plugin.server.getPlayer(state.adminId)
            val target = plugin.server.getPlayer(state.targetId)
            if (admin == null || target == null) {
                close(state.adminId, "player left")
                continue
            }

            // session keep-alive: the spec session owns the truth about who is
            // being watched; when it stops pointing at our target, we stop too
            if (tickCount % KEEP_ALIVE_EVERY == 0L) {
                val sessionTarget = runCatching { state.session.currentTarget() }.getOrNull()
                val matches = sessionTarget != null && sessionTarget.isPresent &&
                    sessionTarget.get() == state.targetId
                if (!matches) {
                    close(state.adminId, "spec session no longer targets this player")
                    continue
                }
            }

            // slow safety backstop: events can't cover every way a plugin may
            // change an inventory, so re-read everything once in a while
            if (now - state.lastFullAtMs >= config.liveInventoryFullResyncMs) state.needsFull.set(true)

            if (state.needsFull.compareAndSet(true, false)) {
                scheduleFullPush(state, admin)
                continue
            }
            if (!state.dirty.get()) continue
            if (!state.dirty.compareAndSet(true, false)) continue
            if (!state.throttle.tryAcquire()) {
                // too soon: keep the change pending for the next flush
                state.dirty.set(true)
                continue
            }
            scheduleDiffPush(state, target)
        }
    }

    /** diff push: re-read the target on its own thread, send only changed slots */
    private fun scheduleDiffPush(state: ViewerState, target: Player) {
        target.scheduler.run(plugin, { task ->
            try {
                if (state.closed) return@run
                val snapshot = captureTargetDisplay(target)
                val old = state.display
                state.display = snapshot
                if (SlotDiff.needsFullResync(old, snapshot)) {
                    state.needsFull.set(true)
                    return@run
                }
                val changed = SlotDiff.changedSlots(old, snapshot)
                if (changed.isEmpty()) return@run

                val windowId = state.windowId
                val stateId = state.stateId.get()
                if (windowId == ViewerState.UNKNOWN_WINDOW || stateId == ViewerState.UNKNOWN_STATE) {
                    // window identity not on the wire yet; the full push sorts it out
                    state.needsFull.set(true)
                    return@run
                }
                for (slot in changed) {
                    sendPacket(
                        state.adminId,
                        WrapperPlayServerSetSlot(windowId, stateId, slot, snapshot[slot]),
                    )
                }
            } finally {
                task.cancel()
            }
        }, null)
    }

    /** full push: rebuild the whole window packet, needs the admin's own panel too */
    private fun scheduleFullPush(state: ViewerState, admin: Player) {
        admin.scheduler.run(plugin, { task ->
            try {
                if (state.closed) return@run
                val windowId = state.windowId
                val stateId = state.stateId.get()
                if (windowId == ViewerState.UNKNOWN_WINDOW || stateId == ViewerState.UNKNOWN_STATE) {
                    state.needsFull.set(true)
                    return@run
                }
                val top = state.display
                if (top.isEmpty()) {
                    // target snapshot hasn't landed yet (open chain still running)
                    state.needsFull.set(true)
                    return@run
                }
                // bottom panel = the admin's own real storage, read on the
                // admin's own thread and sent as-is
                val bottom = admin.inventory.storageContents.map { it.toPacketItem() }
                val items = top + bottom
                state.throttle.markPushed()
                state.lastFullAtMs = System.currentTimeMillis()
                sendPacket(
                    state.adminId,
                    WrapperPlayServerWindowItems(windowId, stateId, items, null),
                )
            } finally {
                task.cancel()
            }
        }, null)
    }

    // === target-side capture (target's entity scheduler only) ================

    private fun captureTargetDisplay(target: Player): List<PacketItem> {
        val inv = target.inventory
        val mapped = ViewerLayout.map(
            mainInventory = (0..35).map { inv.getItem(it).toPacketItem() },
            helmet = inv.helmet.toPacketItem(),
            chestplate = inv.chestplate.toPacketItem(),
            leggings = inv.leggings.toPacketItem(),
            boots = inv.boots.toPacketItem(),
            offhand = inv.itemInOffHand.toPacketItem(),
            held = inv.itemInMainHand.toPacketItem(),
        )
        // empty slots serialize as the packetevents air constant, never null,
        // so diffs and rewrites never have to think about null entries
        return mapped.map { it ?: PacketItem.EMPTY }
    }

    private fun ItemStack?.toPacketItem(): PacketItem =
        if (this == null || type.isAir) PacketItem.EMPTY
        else SpigotConversionUtil.fromBukkitItemStack(this)

    private fun title(target: Player): Component =
        i18n.component(TITLE_KEY, "target" to target.name)

    private fun sendPacket(adminId: UUID, wrapper: com.github.retrooper.packetevents.wrapper.PacketWrapper<*>) {
        val admin = plugin.server.getPlayer(adminId) ?: return
        PacketEvents.getAPI().playerManager.sendPacket(admin, wrapper)
    }

    // === admin side: read-only enforcement ===================================

    @EventHandler(priority = EventPriority.LOWEST)
    fun onViewerClick(event: InventoryClickEvent) {
        val admin = event.whoClicked as? Player ?: return
        if (!states.containsKey(admin.uniqueId)) return
        val topIsViewer = event.view.topInventory.holder is ViewerHolder
        if (ReadOnlyPolicy.onClick(topIsViewer) == ViewerAction.CANCEL_EVENT) {
            event.isCancelled = true
        }
    }

    /** creative-mode clicks bypass the normal click path but share the handler list */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onViewerCreativeClick(event: InventoryCreativeEvent) {
        val admin = event.whoClicked as? Player ?: return
        if (!states.containsKey(admin.uniqueId)) return
        val topIsViewer = event.view.topInventory.holder is ViewerHolder
        if (ReadOnlyPolicy.onCreativeClick(topIsViewer) == ViewerAction.CANCEL_EVENT) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onViewerDrag(event: InventoryDragEvent) {
        val admin = event.whoClicked as? Player ?: return
        if (!states.containsKey(admin.uniqueId)) return
        val topSize = event.view.topInventory.size
        val touchesViewer = event.rawSlots.any { it < topSize }
        if (ReadOnlyPolicy.onDrag(touchesViewer) == ViewerAction.CANCEL_EVENT) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onViewerClose(event: InventoryCloseEvent) {
        val admin = event.player as? Player ?: return
        val state = states[admin.uniqueId] ?: return
        if (event.inventory.holder is ViewerHolder) endViaCloseEvent(state)
    }

    /** foreign container opens lose against the active viewer */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onForeignOpen(event: InventoryOpenEvent) {
        val admin = event.player as? Player ?: return
        if (!states.containsKey(admin.uniqueId)) return
        if (event.inventory.holder is ViewerHolder) return
        if (ReadOnlyPolicy.onForeignOpen(viewerActive = true) == ViewerAction.CANCEL_EVENT) {
            event.isCancelled = true
        }
    }

    // === cleanup: quits =======================================================

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        close(uuid, "player quit")
        for (state in states.values.toList()) {
            if (state.targetId == uuid) close(state.adminId, "target quit")
        }
    }

    // === target side: dirty triggers =========================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTargetClick(event: InventoryClickEvent) = markTargetDirty(event.whoClicked.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTargetDrag(event: InventoryDragEvent) = markTargetDirty(event.whoClicked.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTargetPickup(event: EntityPickupItemEvent) {
        (event.entity as? Player)?.let { markTargetDirty(it.uniqueId) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTargetDrop(event: PlayerDropItemEvent) = markTargetDirty(event.player.uniqueId)

    /** hotbar scroll moves the held-item mirror */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onTargetHeld(event: PlayerItemHeldEvent) = markTargetDirty(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onTargetWorldChange(event: PlayerChangedWorldEvent) {
        val uuid = event.player.uniqueId
        for (state in states.values) {
            if (state.targetId == uuid) {
                state.dirty.set(true)
                state.needsFull.set(true)
            }
        }
    }

    /** hoppers and other automation moving items out of / into the target's inventory */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTargetItemsMoved(event: InventoryMoveItemEvent) {
        (event.source.holder as? Player)?.let { markTargetDirty(it.uniqueId) }
        (event.destination.holder as? Player)?.let { markTargetDirty(it.uniqueId) }
    }

    private fun markTargetDirty(targetId: UUID) {
        if (states.isEmpty()) return
        for (state in states.values) {
            if (state.targetId == targetId) state.dirty.set(true)
        }
    }

    private companion object {
        const val TITLE_KEY = "spec.inventory-title"

        /** flush cadence: every 5 ticks, so worst-case update latency ~ push interval + 250ms */
        const val FLUSH_PERIOD_TICKS = 5L
        const val FLUSH_DELAY_TICKS = 20L

        /** keep-alive probe cadence in flush cycles (~1s) */
        const val KEEP_ALIVE_EVERY = 4L
    }
}
