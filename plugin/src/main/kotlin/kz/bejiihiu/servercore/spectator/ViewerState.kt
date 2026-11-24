package kz.bejiihiu.servercore.spectator

import com.github.retrooper.packetevents.protocol.item.ItemStack
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * live state of one open viewer, shared across threads: the target's region
 * thread snapshots the inventory, the admin's region thread owns the window,
 * the netty thread rewrites packets, the global scheduler drives the flush.
 * every field is therefore volatile/atomic and no bukkit objects live here —
 * items are packetevents value copies, safe to read and reuse from anywhere.
 */
class ViewerState(
    val adminId: UUID,
    val targetId: UUID,
    val session: SpecSessionReader,
    val throttle: PushThrottle,
) {
    /**
     * display slots as packet items, indexed exactly like the backing
     * container (see [ViewerLayout]); never null entries, empty slots are
     * [ItemStack.EMPTY]. writers publish a fresh immutable list, readers on
     * other threads see either the old or the new list, never a torn one.
     */
    @Volatile
    var display: List<ItemStack> = emptyList()

    /** window id of the backing container, captured from the outgoing open-window packet */
    @Volatile
    var windowId: Int = UNKNOWN_WINDOW

    /** last container state id seen on the wire, reused for our injected packets */
    val stateId = AtomicInteger(UNKNOWN_STATE)

    /** next push must be a full window-items packet (open, world change, backstop) */
    val needsFull = AtomicBoolean(true)

    /** a target-side change was observed since the last push */
    val dirty = AtomicBoolean(false)

    /** wall clock of the last full resync, drives the safety backstop */
    @Volatile
    var lastFullAtMs: Long = 0L

    /** single-shot close marker; once true, all async work bails out */
    @Volatile
    var closed = false

    companion object {
        const val UNKNOWN_WINDOW = -1
        const val UNKNOWN_STATE = -1
    }
}
