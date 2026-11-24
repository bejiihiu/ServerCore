package kz.bejiihiu.servercore.spectator

import java.util.UUID
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/**
 * marker holder: every inventory event under this holder belongs to the
 * read-only viewer and gets rejected, never to a real chest. the backing
 * container is an empty 6-row chest that nobody but the packet listener ever
 * fills — what the admin sees on screen is packet-level fiction drawn from the
 * target's snapshot.
 */
class ViewerHolder(
    val viewerId: UUID,
    val targetId: UUID,
) : InventoryHolder {

    private var attached: Inventory? = null

    /** called once right after createInventory, before the holder is used */
    fun attach(inventory: Inventory) {
        attached = inventory
    }

    override fun getInventory(): Inventory =
        attached ?: error("viewer holder used before its backing inventory was attached")
}
