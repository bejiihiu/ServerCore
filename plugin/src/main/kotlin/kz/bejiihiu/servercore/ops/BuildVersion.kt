package kz.bejiihiu.servercore.ops

/**
 * version scheme used by this project: `build-<commitCount>-<shortSha>`.
 * no semver on purpose (product decision) - releases are ordered by the
 * monotonically growing commit count; the short sha only identifies the
 * exact commit.
 */
data class BuildVersion(val commitCount: Int, val sha: String) {

    fun tag(): String = if (sha.isEmpty()) "build-$commitCount" else "build-$commitCount-$sha"

    companion object {
        private val PATTERN = Regex("""^build-(\d{1,9})(?:-([0-9a-fA-F]{7,40}))?$""")

        /** null for anything that is not a build-* tag (e.g. legacy 0.1.0) */
        fun parse(raw: String?): BuildVersion? {
            if (raw.isNullOrBlank()) return null
            val match = PATTERN.find(raw.trim()) ?: return null
            val count = match.groupValues[1].toIntOrNull() ?: return null
            return BuildVersion(count, match.groupValues[2].lowercase())
        }

        /**
         * total ordering: by commit count, then sha as a tiebreaker. note that
         * two different shas at the same count are divergent builds - ordering
         * them lexicographically is only for sorted displays, the update
         * decision never treats one as "newer" than the other.
         */
        val COMPARATOR: Comparator<BuildVersion> = compareBy({ it.commitCount }, { it.sha })

        /** true only when candidate is strictly ahead of current */
        fun isNewer(candidate: BuildVersion, current: BuildVersion): Boolean =
            candidate.commitCount > current.commitCount
    }
}
