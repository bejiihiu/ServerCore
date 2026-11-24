package kz.bejiihiu.servercore.guest

import io.papermc.paper.event.player.AsyncChatEvent
import java.util.UUID
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.inventory.InventoryHolder

/**
 * the §10.3 wall. every handler is a cheap membership check plus cancel —
 * events fire on region threads (chat on async), cancelling is thread-safe,
 * and no state is mutated here. hints are throttled so a flapping guest does
 * not get spammed.
 *
 * chat isolation runs at LOWEST so flectonepulse (or any other chat plugin)
 * never sees guest traffic in the first place — no fighting over the event.
 */
class GuestProtectionListener(
    private val sessions: GuestSessionService,
    private val config: GuestConfig,
    private val messages: GuestMessageService,
) : Listener {

    private val hintCooldown = java.util.concurrent.ConcurrentHashMap<UUID, Long>()

    private fun restricted(uuid: UUID): Boolean = sessions.isRestricted(uuid)

    private fun hint(player: org.bukkit.entity.Player) {
        val now = System.currentTimeMillis()
        val last = hintCooldown[player.uniqueId] ?: 0
        if (now - last < HINT_COOLDOWN_MS) return
        hintCooldown[player.uniqueId] = now
        messages.sendRestrictionHint(player)
    }

    // --- chat isolation -------------------------------------------------------

    /** guest writing: cancelled before any chat plugin can pick it up */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onGuestChat(event: AsyncChatEvent) {
        if (!config.restrictions.chat) return
        val player = event.player
        if (!restricted(player.uniqueId)) return
        event.isCancelled = true
        hint(player)
    }

    /** guest reading: dropped from the recipient set of everyone else's chat */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onChatVisibility(event: AsyncChatEvent) {
        if (!config.restrictions.chat) return
        event.viewers().removeIf { viewer ->
            viewer is org.bukkit.entity.Player && restricted(viewer.uniqueId)
        }
    }

    // --- world ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (!config.restrictions.blockBreak) return
        if (restricted(event.player.uniqueId)) {
            event.isCancelled = true
            hint(event.player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (!config.restrictions.blockPlace) return
        if (restricted(event.player.uniqueId)) {
            event.isCancelled = true
            hint(event.player)
        }
    }

    /** block interaction and item use both come through here (right/left click) */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (!config.restrictions.blockInteraction) return
        if (restricted(event.player.uniqueId)) {
            event.isCancelled = true
            hint(event.player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPortal(event: PlayerPortalEvent) {
        if (!config.restrictions.portals) return
        if (restricted(event.player.uniqueId)) {
            event.isCancelled = true
            hint(event.player)
        }
    }

    // --- entities -------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        if (!config.restrictions.entityDamage) return
        val damager = event.damager as? org.bukkit.entity.Player ?: return
        if (restricted(damager.uniqueId)) {
            event.isCancelled = true
            hint(damager)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        if (!config.restrictions.itemPickup) return
        val player = event.entity as? org.bukkit.entity.Player ?: return
        if (restricted(player.uniqueId)) {
            event.isCancelled = true
            hint(player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (!config.restrictions.inventoryEdit) return
        if (restricted(event.player.uniqueId)) {
            event.isCancelled = true
            hint(event.player)
        }
    }

    // --- inventory and containers ----------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onContainerOpen(event: InventoryOpenEvent) {
        if (!config.restrictions.containerInteraction) return
        val player = event.player as? org.bukkit.entity.Player ?: return
        if (restricted(player.uniqueId)) {
            event.isCancelled = true
            hint(player)
        }
    }

    /** covers chest/any-holder clicks too: the top inventory belongs to a container */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? org.bukkit.entity.Player ?: return
        if (!restricted(player.uniqueId)) return
        val touchesContainer = event.clickedInventory?.holder?.let { isContainer(it) } == true
        if (touchesContainer && config.restrictions.containerInteraction) {
            event.isCancelled = true
            hint(player)
            return
        }
        if (config.restrictions.inventoryEdit) {
            event.isCancelled = true
            hint(player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (!config.restrictions.inventoryEdit) return
        val player = event.whoClicked as? org.bukkit.entity.Player ?: return
        if (restricted(player.uniqueId)) {
            event.isCancelled = true
            hint(player)
        }
    }

    private fun isContainer(holder: InventoryHolder): Boolean =
        holder !is org.bukkit.entity.Player && holder !is org.bukkit.inventory.PlayerInventory

    // --- mode and commands ------------------------------------------------------

    /** outside transitions the session service performs itself, guests stay put */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        if (!config.restrictions.gamemodeChange) return
        val uuid = event.player.uniqueId
        if (sessions.isInModeTransition(uuid)) return
        if (restricted(uuid)) {
            event.isCancelled = true
            hint(event.player)
        }
    }

    /**
     * whitelisted commands pass through, except "link" which we answer ourselves
     * instead of letting the server print "unknown command". everything else is
     * cancelled before execution.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        if (!restricted(player.uniqueId)) return
        val line = event.message
        if (CommandGate.isAllowed(line, config.commandWhitelist)) {
            if (CommandGate.extract(line) == LINK_COMMAND) {
                event.isCancelled = true
                messages.sendPrompt(player)
            }
            return
        }
        if (config.restrictions.commands) {
            event.isCancelled = true
            hint(player)
        }
    }

    private companion object {
        const val HINT_COOLDOWN_MS = 3000L
        const val LINK_COMMAND = "link"
    }
}
