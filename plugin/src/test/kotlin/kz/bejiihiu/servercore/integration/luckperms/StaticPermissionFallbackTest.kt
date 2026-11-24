package kz.bejiihiu.servercore.integration.luckperms

import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticPermissionFallbackTest {

    private val uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
    private val other = UUID.fromString("46c4e6b3-1c9d-4d33-8d4f-2b4f1a4a55e1")

    private fun fallback(
        everyone: List<String> = emptyList(),
        players: Map<UUID, List<String>> = emptyMap(),
        groups: Map<UUID, String> = emptyMap(),
        defaultGroup: String = "default",
    ) = StaticPermissionFallback(
        config = LuckPermsFallbackConfig(
            defaultGroup = defaultGroup,
            groups = groups,
            everyonePermissions = everyone,
            playerPermissions = players,
        ),
        logger = Logger.getLogger("test"),
    )

    @Test
    fun exactNodeGrantsForThePlayer() {
        val source = fallback(players = mapOf(uuid to listOf("servercore.admin")))
        assertTrue(source.hasPermission(uuid, "servercore.admin").awaitCompleted())
    }

    @Test
    fun unlistedNodesDenyByDefault() {
        val source = fallback(players = mapOf(uuid to listOf("servercore.admin")))
        assertFalse(source.hasPermission(uuid, "servercore.spec").awaitCompleted())
        assertFalse(source.hasPermission(other, "servercore.admin").awaitCompleted())
    }

    @Test
    fun everyoneListAppliesToAllPlayers() {
        val source = fallback(everyone = listOf("servercore.spec"))
        assertTrue(source.hasPermission(uuid, "servercore.spec").awaitCompleted())
        assertTrue(source.hasPermission(other, "servercore.spec").awaitCompleted())
    }

    @Test
    fun starGrantsEverything() {
        val source = fallback(players = mapOf(uuid to listOf("*")))
        assertTrue(source.hasPermission(uuid, "anything.at.all").awaitCompleted())
    }

    @Test
    fun prefixWildcardMatchesSubtreeAndRoot() {
        val source = fallback(players = mapOf(uuid to listOf("servercore.*")))
        assertTrue(source.hasPermission(uuid, "servercore.admin").awaitCompleted())
        assertTrue(source.hasPermission(uuid, "servercore.spec.other").awaitCompleted())
        assertFalse(source.hasPermission(uuid, "servercorex.admin").awaitCompleted())
        assertFalse(source.hasPermission(uuid, "other.node").awaitCompleted())
    }

    @Test
    fun groupsFallBackToTheDefaultGroup() {
        assertEquals(
            PlayerGroups(primary = "default", groups = setOf("default")),
            fallback().groups(uuid).awaitCompleted(),
        )
    }

    @Test
    fun configuredGroupsOverrideTheDefault() {
        val source = fallback(groups = mapOf(uuid to "admin"))
        assertEquals(
            PlayerGroups(primary = "admin", groups = setOf("admin")),
            source.groups(uuid).awaitCompleted(),
        )
        assertEquals(RankGroup.ADMIN, source.topRank(uuid).awaitCompleted())
    }

    @Test
    fun topRankIsDefaultWhenNothingIsConfigured() {
        assertEquals(RankGroup.DEFAULT, fallback().topRank(uuid).awaitCompleted())
    }

    @Test
    fun groupMutationsAreLoudlyRejected() {
        val source = fallback()
        assertEquals(GroupMutationResult.REJECTED, source.addGroup(uuid, RankGroup.TRUSTED).awaitCompleted())
        assertEquals(GroupMutationResult.REJECTED, source.removeGroup(uuid, RankGroup.TRUSTED).awaitCompleted())
    }

    @Test
    fun recalculationBridgeExistsButNeverFires() {
        val source = fallback()
        var fired = 0
        val subscription = source.recalculationBridge.subscribe { _ -> fired++ }
        subscription.close()
        assertEquals(0, fired)
    }
}
