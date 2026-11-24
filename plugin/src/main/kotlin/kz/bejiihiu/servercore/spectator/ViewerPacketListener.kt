package kz.bejiihiu.servercore.spectator

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import org.bukkit.entity.Player

/**
 * outgoing packet rewrites for the viewer window. the server believes the
 * admin opened an empty 6-row chest; every window-items / set-slot packet for
 * that window gets its top 54 slots rewritten from the target's snapshot, so
 * the client always sees the target's inventory no matter who initiated the
 * packet — including our own injected pushes, the rewrite is idempotent
 * because display indexes and backing container indexes are identical.
 *
 * the bottom 36 slots are never touched: that part of the screen shows the
 * admin's own real inventory and must stay truthful.
 *
 * runs on the netty thread — only volatile/atomic viewer state is read here.
 */
class ViewerPacketListener(
    private val viewers: InventoryViewerService,
) : PacketListenerAbstract(PacketListenerPriority.HIGH) {

    override fun onPacketSend(event: PacketSendEvent) {
        if (event.isCancelled) return
        when (event.packetType) {
            PacketType.Play.Server.OPEN_WINDOW -> captureWindowId(event)
            PacketType.Play.Server.WINDOW_ITEMS -> rewriteWindowItems(event)
            PacketType.Play.Server.SET_SLOT -> rewriteSetSlot(event)
            else -> return
        }
    }

    /** remember which window id the server handed the admin's viewer container */
    private fun captureWindowId(event: PacketSendEvent) {
        val state = viewers.stateFor(event) ?: return
        val captured = WrapperPlayServerOpenWindow(event).containerId
        val previous = state.windowId
        state.windowId = captured
        // a different window opened over ours means the view is gone — the
        // open-event guard cancels normal cases, this catches packet-level ones
        if (previous != ViewerState.UNKNOWN_WINDOW && previous != captured) {
            viewers.close(state.adminId, "window replaced by window $captured")
        }
    }

    private fun rewriteWindowItems(event: PacketSendEvent) {
        val state = viewers.stateFor(event) ?: return
        val wrapper = WrapperPlayServerWindowItems(event)
        if (wrapper.windowId != state.windowId) return
        state.stateId.set(wrapper.stateId)

        val snapshot = state.display
        if (snapshot.isEmpty()) return // nothing captured yet, leave the packet alone
        val items = ArrayList(wrapper.items)
        for (i in 0 until minOf(ViewerLayout.SIZE, items.size)) {
            items[i] = snapshot[i]
        }
        wrapper.setItems(items)
    }

    private fun rewriteSetSlot(event: PacketSendEvent) {
        val state = viewers.stateFor(event) ?: return
        val wrapper = WrapperPlayServerSetSlot(event)
        if (wrapper.windowId != state.windowId) return
        state.stateId.set(wrapper.stateId)

        val slot = wrapper.slot
        if (slot < 0 || slot >= ViewerLayout.SIZE) return
        val snapshot = state.display
        if (snapshot.isEmpty()) return
        wrapper.item = snapshot[slot]
    }

    private fun InventoryViewerService.stateFor(event: PacketSendEvent): ViewerState? {
        val player = event.getPlayer<Player>() ?: return null
        return stateFor(player.uniqueId)
    }
}
