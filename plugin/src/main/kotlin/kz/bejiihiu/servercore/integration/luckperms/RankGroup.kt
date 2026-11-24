package kz.bejiihiu.servercore.integration.luckperms

import java.util.Locale

/**
 * the servercore rank ladder, ordered by weight. used for the protection
 * level ordering: a player's rank is the heaviest group luckperms reports
 * for them (owner > admin > moderator > helper > trusted > default).
 */
enum class RankGroup(val weight: Int, val configName: String) {
    DEFAULT(0, "default"),
    TRUSTED(1, "trusted"),
    HELPER(2, "helper"),
    MODERATOR(3, "moderator"),
    ADMIN(4, "admin"),
    OWNER(5, "owner"),
    ;

    /** true when this rank is at least as heavy as [other] */
    fun atLeast(other: RankGroup): Boolean = weight >= other.weight

    companion object {

        /** matches a luckperms group name case-insensitively, null when it is not a rank */
        fun fromName(name: String?): RankGroup? {
            val trimmed = name?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.configName == trimmed }
        }

        /**
         * the effective rank of a set of group names: the highest recognised
         * group wins, unknown groups are ignored. null when none match.
         */
        fun highestOf(groupNames: Collection<String>): RankGroup? =
            groupNames.mapNotNull(::fromName).maxByOrNull { it.weight }
    }
}
