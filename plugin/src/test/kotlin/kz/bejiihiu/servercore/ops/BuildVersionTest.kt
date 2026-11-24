package kz.bejiihiu.servercore.ops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildVersionTest {

    @Test
    fun parsesTheCanonicalScheme() {
        val version = BuildVersion.parse("build-42-a1b2c3d")
        assertEquals(BuildVersion(42, "a1b2c3d"), version)
        assertEquals("build-42-a1b2c3d", version!!.tag())
    }

    @Test
    fun parsesFullSha() {
        val version = BuildVersion.parse("build-1234-0123456789abcdef0123456789abcdef01234567")
        assertEquals(1234, version!!.commitCount)
        assertEquals(40, version.sha.length)
    }

    @Test
    fun parsesTagWithoutSha() {
        val version = BuildVersion.parse("build-7")
        assertEquals(BuildVersion(7, ""), version)
        assertEquals("build-7", version!!.tag())
    }

    @Test
    fun normalizesShaCase() {
        assertEquals(BuildVersion(9, "abc1234"), BuildVersion.parse("build-9-ABC1234"))
    }

    @Test
    fun rejectsJunk() {
        assertNull(BuildVersion.parse(null))
        assertNull(BuildVersion.parse(""))
        assertNull(BuildVersion.parse("0.1.0"))
        assertNull(BuildVersion.parse("v1.2.3"))
        assertNull(BuildVersion.parse("build-"))
        assertNull(BuildVersion.parse("build-abc-def"))
        assertNull(BuildVersion.parse("build-12-abc")) // sha too short
        assertNull(BuildVersion.parse("build-12-abc1234-extra"))
        assertNull(BuildVersion.parse("release-1"))
        assertNull(BuildVersion.parse(" build-1-abcdefg junk"))
    }

    @Test
    fun newerByCommitCount() {
        val old = BuildVersion.parse("build-41-aaaaaaa")!!
        val new = BuildVersion.parse("build-42-bbbbbbb")!!
        assertTrue(BuildVersion.isNewer(new, old))
        assertFalse(BuildVersion.isNewer(old, new))
    }

    @Test
    fun sameBuildIsNotNewer() {
        val a = BuildVersion.parse("build-42-abc1234")!!
        val b = BuildVersion.parse("build-42-abc1234")!!
        assertFalse(BuildVersion.isNewer(b, a))
    }

    @Test
    fun divergentBuildsAtSameCountAreNotNewer() {
        val a = BuildVersion.parse("build-42-abc1234")!!
        val b = BuildVersion.parse("build-42-def5678")!!
        // same count, different sha: cannot be ordered as an update
        assertFalse(BuildVersion.isNewer(b, a))
        assertFalse(BuildVersion.isNewer(a, b))
    }

    @Test
    fun comparatorOrdersByCountThenSha() {
        val versions = listOf(
            BuildVersion.parse("build-10-ffff111")!!,
            BuildVersion.parse("build-2-aaaaaaa")!!,
            BuildVersion.parse("build-10-0001111")!!,
            BuildVersion.parse("build-3-ccccccc")!!,
        )
        val sorted = versions.sortedWith(BuildVersion.COMPARATOR)
        assertEquals(
            listOf("build-2-aaaaaaa", "build-3-ccccccc", "build-10-0001111", "build-10-ffff111"),
            sorted.map { it.tag() },
        )
    }

    @Test
    fun hugeCommitCountsDoNotOverflowInt() {
        // 10 digits exceed the {1,9} cap on purpose - malformed, not parsed
        assertNull(BuildVersion.parse("build-1234567890-abcdef1"))
    }
}
