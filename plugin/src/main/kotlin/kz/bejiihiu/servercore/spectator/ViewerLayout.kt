package kz.bejiihiu.servercore.spectator

/**
 * pure display mapping for the 6-row viewer window. display slot indexes are
 * deliberately identical to the backing container's server-side indexes
 * (0..53), so packet rewrites stay a plain lookup instead of a per-window
 * translation table — the value a server set-slot packet carries is the same
 * index our snapshot is stored under.
 *
 * layout of the visible window:
 * ```
 *   rows 0-2   0..26   main inventory, storage slots 9..35
 *   row 3      27..35  hotbar slots 0..8
 *   row 4      36..39  armor: helmet, chestplate, leggings, boots
 *              40      offhand
 *              41      held-item mirror (main hand, always visible)
 *   row 5      42..53  filler, always empty
 * ```
 * the held-item mirror exists so the main hand is readable at a glance even
 * when the hotbar selection is buried among nine slots.
 */
object ViewerLayout {

    /** total backing container size: 6 rows x 9 columns */
    const val SIZE = 54

    /** last display slot that can carry an item; everything past it is filler */
    const val SLOTS_SHOWN = 42

    const val MAIN_START = 0
    const val HOTBAR_START = 27
    const val ARMOR_START = 36
    const val OFFHAND = 40
    const val HELD = 41

    /**
     * maps the target's inventory pieces into display order. [mainInventory]
     * must be exactly 36 entries in bukkit player-inventory order (0..8
     * hotbar, then 9..35 storage). filler slots stay null; the caller decides
     * what an empty slot serializes as.
     */
    fun <T> map(
        mainInventory: List<T>,
        helmet: T?,
        chestplate: T?,
        leggings: T?,
        boots: T?,
        offhand: T?,
        held: T?,
    ): List<T?> {
        require(mainInventory.size == 36) { "main inventory must be 36 slots, got ${mainInventory.size}" }
        @Suppress("UNCHECKED_CAST")
        val out = arrayOfNulls<Any>(SIZE) as Array<T?>
        for (storage in 9..35) out[MAIN_START + storage - 9] = mainInventory[storage]
        for (hotbar in 0..8) out[HOTBAR_START + hotbar] = mainInventory[hotbar]
        out[ARMOR_START] = helmet
        out[ARMOR_START + 1] = chestplate
        out[ARMOR_START + 2] = leggings
        out[ARMOR_START + 3] = boots
        out[OFFHAND] = offhand
        out[HELD] = held
        return out.toList()
    }
}
