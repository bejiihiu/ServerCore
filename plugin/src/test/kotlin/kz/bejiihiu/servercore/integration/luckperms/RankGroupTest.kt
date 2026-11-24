package kz.bejiihiu.servercore.integration.luckperms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RankGroupTest {

    @Test
    fun weightsFollowTheLadder() {
        assertTrue(RankGroup.OWNER.atLeast(RankGroup.OWNER))
        assertTrue(RankGroup.OWNER.atLeast(RankGroup.ADMIN))
        assertTrue(RankGroup.ADMIN.atLeast(RankGroup.MODERATOR))
        assertTrue(RankGroup.MODERATOR.atLeast(RankGroup.HELPER))
        assertTrue(RankGroup.HELPER.atLeast(RankGroup.TRUSTED))
        assertTrue(RankGroup.TRUSTED.atLeast(RankGroup.DEFAULT))
    }

    @Test
    fun lowerRanksNeverBeatHigherOnes() {
        assertTrue(!RankGroup.DEFAULT.atLeast(RankGroup.TRUSTED))
        assertTrue(!RankGroup.MODERATOR.atLeast(RankGroup.ADMIN))
    }

    @Test
    fun fromNameMatchesCaseInsensitively() {
        assertEquals(RankGroup.OWNER, RankGroup.fromName("Owner"))
        assertEquals(RankGroup.MODERATOR, RankGroup.fromName("  MODERATOR "))
        assertEquals(RankGroup.DEFAULT, RankGroup.fromName("default"))
    }

    @Test
    fun fromNameRejectsUnknownAndBlank() {
        assertNull(RankGroup.fromName("vip"))
        assertNull(RankGroup.fromName(""))
        assertNull(RankGroup.fromName("   "))
        assertNull(RankGroup.fromName(null))
    }

    @Test
    fun highestOfPicksTheHeaviestGroup() {
        assertEquals(RankGroup.OWNER, RankGroup.highestOf(listOf("default", "admin", "owner")))
        assertEquals(RankGroup.MODERATOR, RankGroup.highestOf(listOf("trusted", "Moderator")))
        assertEquals(RankGroup.DEFAULT, RankGroup.highestOf(listOf("default")))
    }

    @Test
    fun highestOfIgnoresUnknownGroups() {
        assertEquals(RankGroup.ADMIN, RankGroup.highestOf(listOf("vip", "builder", "admin")))
        assertNull(RankGroup.highestOf(listOf("vip", "builder")))
        assertNull(RankGroup.highestOf(emptyList()))
    }
}
