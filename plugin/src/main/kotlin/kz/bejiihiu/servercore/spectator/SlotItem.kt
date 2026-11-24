package kz.bejiihiu.servercore.spectator

/**
 * server-agnostic snapshot of one inventory slot: plain data so the layout,
 * diff and policy logic stays unit-testable without a bukkit or packetevents
 * classpath on the test source set. null (absent) means an empty slot.
 *
 * [stamp] folds in everything beyond type and count (nbt, components, damage,
 * enchantments...) — the capture layer derives it from the live ItemStack's
 * hashCode, so any meta change shows up as a diff without this layer knowing
 * what "meta" even is.
 */
data class SlotItem(
    val material: String,
    val count: Int,
    val stamp: Int,
)
