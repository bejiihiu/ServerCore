package kz.bejiihiu.servercore.ops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UpdatePolicyTest {

    private fun candidate(tag: String) = ReleaseCandidate(
        tag = tag,
        version = BuildVersion.parse(tag),
        jarUrl = "https://example.com/$tag.jar",
        jarAssetName = "ServerCore-$tag-all.jar",
        checksumUrl = "https://example.com/SHA256SUMS.txt",
        checksumAssetName = "SHA256SUMS.txt",
    )

    @Test
    fun noReleaseMeansNothingToDo() {
        assertIs<UpdateDecision.NoRelease>(UpdatePolicy.decide("build-42-abc1234", null))
    }

    @Test
    fun upToDateWhenRunningTheLatestRelease() {
        val decision = UpdatePolicy.decide("build-42-abc1234", candidate("build-42-abc1234"))
        assertIs<UpdateDecision.UpToDate>(decision)
    }

    @Test
    fun updateWhenCommitCountGrows() {
        val decision = UpdatePolicy.decide("build-42-abc1234", candidate("build-43-def5678"))
        assertIs<UpdateDecision.Available>(decision)
        assertEquals("build-43-def5678", decision.release.tag)
        assertEquals("build-42-abc1234", decision.currentRaw)
    }

    @Test
    fun olderReleaseIsIgnored() {
        assertIs<UpdateDecision.UpToDate>(UpdatePolicy.decide("build-99-abc1234", candidate("build-42-def5678")))
    }

    @Test
    fun divergentBuildsAtSameCountAreNotAnUpdate() {
        assertIs<UpdateDecision.UpToDate>(UpdatePolicy.decide("build-42-abc1234", candidate("build-42-def5678")))
    }

    @Test
    fun devVersionGetsTheRelease() {
        val decision = UpdatePolicy.decide("0.1.0", candidate("build-5-abc1234"))
        assertIs<UpdateDecision.Available>(decision)
    }

    @Test
    fun nullVersionGetsTheRelease() {
        assertIs<UpdateDecision.Available>(UpdatePolicy.decide(null, candidate("build-5-abc1234")))
    }

    @Test
    fun unparsableCurrentNeverReinstallsTheSameTag() {
        // a current string that happens to parse to the same version is just up to date
        assertIs<UpdateDecision.UpToDate>(UpdatePolicy.decide("build-5-abc1234", candidate("build-5-abc1234")))
    }

    @Test
    fun releaseWithoutParsableTagIsIgnored() {
        assertIs<UpdateDecision.UpToDate>(UpdatePolicy.decide("build-42-abc1234", candidate("v1.0.0")))
    }
}
