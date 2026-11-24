package kz.bejiihiu.servercore.protection

/**
 * read-only snapshot of the live command tree: which literal names resolve to
 * one and the same underlying command. pure data so alias expansion can be
 * unit-tested against a fake tree.
 */
interface CommandTreeSnapshot {

    /** every group of literal names that all point at the same command */
    fun aliasGroups(): Collection<Set<String>>
}

/**
 * walks alias groups outward from the configured blocked names: blocking "tp"
 * automatically covers "teleport" and every other label that resolves to the
 * same command node, whatever registered it.
 */
object AliasExpander {

    fun expand(blockedNames: Collection<String>, tree: CommandTreeSnapshot): Set<String> {
        val result = blockedNames.mapTo(mutableSetOf()) { it.lowercase() }
        var frontier: Set<String> = result
        // groups form an equivalence relation, so one pass per frontier wave
        // reaches a fixed point; real trees converge after the first wave
        while (frontier.isNotEmpty()) {
            val added = mutableSetOf<String>()
            for (group in tree.aliasGroups()) {
                if (group.none { it.lowercase() in frontier }) continue
                for (name in group) {
                    val lowered = name.lowercase()
                    if (result.add(lowered)) added.add(lowered)
                }
            }
            frontier = added
        }
        return result
    }
}
