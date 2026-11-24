package kz.bejiihiu.servercore.protection

/**
 * pure matching of a typed command line against the blocked set, including
 * live alias expansion. kept free of bukkit types so tests can drive it with
 * a fake command tree.
 */
class BlockedCommandMatcher(
    blockedNames: Collection<String>,
    datapackNames: Collection<String>,
    private val aliasSource: () -> CommandTreeSnapshot,
) {

    /** what kind of deny we hit */
    enum class Kind { BLOCKED, DATAPACK }

    data class Match(val matched: String, val kind: Kind)

    private val blocked: Set<String>
    private val datapack: Set<String> = datapackNames.mapTo(sortedSetOf(), ::normalize)

    init {
        val tree = runCatching { aliasSource() }.getOrNull()
        blocked = if (tree != null) {
            AliasExpander.expand(blockedNames.map(::normalize), tree)
        } else {
            // tree unavailable (server still booting): degrade to the raw
            // names instead of refusing to start, refresh() widens it later
            blockedNames.mapTo(sortedSetOf(), ::normalize)
        }
    }

    /** null when the line is allowed to run */
    fun match(commandLine: String): Match? {
        val head = parseHead(commandLine) ?: return null
        if (head in datapack) return Match(head, Kind.DATAPACK)
        if (head in blocked) return Match(head, Kind.BLOCKED)
        return null
    }

    /** every denied name, bare and expanded — used to strip tab completion */
    fun deniedNames(): Set<String> = blocked + datapack

    companion object {

        /** "/  MineCraft:Tp here" -> "tp", garbage tolerated */
        fun parseHead(commandLine: String): String? {
            val trimmed = commandLine.trim().removePrefix("/")
            if (trimmed.isBlank()) return null
            val head = trimmed.trim().split(' ', limit = 2).first()
            return head.substringAfterLast(':').lowercase().ifEmpty { null }
        }

        /** "/MINECRAFT:Tp" -> "tp" — config entries and typed lines compare equal */
        internal fun normalize(name: String): String =
            name.trim().removePrefix("/").substringAfterLast(':').lowercase()
    }
}
