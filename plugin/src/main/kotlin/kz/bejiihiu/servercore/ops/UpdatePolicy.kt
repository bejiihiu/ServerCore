package kz.bejiihiu.servercore.ops

/**
 * a github release that carries a plugin jar we could update to.
 * plain data - no github sdk dependency, parsed straight from the rest json.
 */
data class ReleaseCandidate(
    val tag: String,
    val version: BuildVersion?,
    /** download url of the (single) plugin jar asset */
    val jarUrl: String,
    val jarAssetName: String,
    /** download url of the checksums asset, null when the release ships none */
    val checksumUrl: String?,
    val checksumAssetName: String?,
)

sealed interface UpdateDecision {
    /** running version already matches or beats the latest release */
    data object UpToDate : UpdateDecision

    /** a newer (or unverifiable-current) release is ready to install */
    data class Available(val release: ReleaseCandidate, val currentRaw: String?) : UpdateDecision

    /** nothing decided: network said nothing useful; caller just logs and waits */
    data object NoRelease : UpdateDecision
}

/**
 * pure decision logic for the auto-updater, separated from IO so it is fully
 * unit-testable.
 */
object UpdatePolicy {

    /**
     * compares the running plugin version against the latest build-* release.
     *
     * - unparsable current version (dev build, legacy 0.1.0): we cannot prove
     *   parity, so the release is offered - worst case it reinstalls the same
     *   artifacts, which is harmless.
     * - same commit count with a different sha: divergent builds, never an
     *   update (we cannot order them).
     */
    fun decide(currentVersionRaw: String?, latest: ReleaseCandidate?): UpdateDecision {
        if (latest == null) return UpdateDecision.NoRelease

        val current = BuildVersion.parse(currentVersionRaw)
        if (current == null) {
            // dev build or legacy version: parity cannot be proven, offer the release
            return UpdateDecision.Available(latest, currentVersionRaw)
        }

        val candidate = latest.version
            ?: return UpdateDecision.UpToDate // release tag is not a build version, ignore it

        return if (BuildVersion.isNewer(candidate, current)) {
            UpdateDecision.Available(latest, currentVersionRaw)
        } else {
            UpdateDecision.UpToDate
        }
    }
}
