package kz.bejiihiu.servercore.spectator

/**
 * pure diff between two display snapshots. works on any element type with
 * structural equality, so tests can feed strings while production feeds
 * packetevents item copies (value objects — equality covers type, count, nbt
 * and components).
 */
object SlotDiff {

    /**
     * display slots whose content changed between [old] and [new]. both lists
     * must describe the same window; a size mismatch means the layout itself
     * moved and the caller must fall back to a full resync instead of trusting
     * per-slot updates.
     */
    fun changedSlots(old: List<*>, new: List<*>): List<Int> {
        require(old.size == new.size) { "snapshot size mismatch: ${old.size} vs ${new.size}" }
        val changed = ArrayList<Int>()
        for (i in old.indices) {
            if (old[i] != new[i]) changed.add(i)
        }
        return changed
    }

    /** true when per-slot updates are meaningless and a full resync is needed */
    fun needsFullResync(old: List<*>, new: List<*>): Boolean = old.size != new.size
}
