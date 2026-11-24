package kz.bejiihiu.servercore.integration.luckperms

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LuckPermsAdapterTest {

    private val uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
    private val logger = Logger.getLogger("test")

    /** the fake lp surface the adapter logic runs against */
    private class FakeUser(
        var granted: Boolean = false,
        groups: Set<String> = emptySet(),
        var primary: String? = null,
    ) : LpUserView {
        val groupSet = groups.toMutableSet()
        val mutations = mutableListOf<String>()

        override fun permissionValue(node: String): Boolean = granted

        override fun groupNames(): Set<String> = groupSet.toSet()

        override fun primaryGroup(): String? = primary

        override fun addGroupNode(group: String): Boolean {
            mutations += "+$group"
            return groupSet.add(group)
        }

        override fun removeGroupNode(group: String): Boolean {
            mutations += "-$group"
            return groupSet.remove(group)
        }
    }

    /** lp's cached-data access blew up mid-check, the adapter must still deny */
    private class ThrowingUser : LpUserView {
        override fun permissionValue(node: String): Boolean = throw IllegalStateException("cached data not loaded")
        override fun groupNames(): Set<String> = emptySet()
        override fun primaryGroup(): String? = null
        override fun addGroupNode(group: String): Boolean = false
        override fun removeGroupNode(group: String): Boolean = false
    }

    private class FakeLpSource : LpUserSource {
        val users = mutableMapOf<UUID, LpUserView>()
        var loadFailure: Throwable? = null
        private val consumers = CopyOnWriteArrayList<(UUID) -> Unit>()

        override fun loadUser(uuid: UUID): CompletableFuture<LpUserView> {
            val failure = loadFailure
            if (failure != null) return CompletableFuture.failedFuture(failure)
            return CompletableFuture.completedFuture(users.getOrPut(uuid) { FakeUser() })
        }

        override fun modifyUser(uuid: UUID, mutation: (LpUserView) -> Boolean): CompletableFuture<Boolean> {
            val failure = loadFailure
            if (failure != null) return CompletableFuture.failedFuture(failure)
            return CompletableFuture.completedFuture(mutation(users.getOrPut(uuid) { FakeUser() }))
        }

        override fun subscribeRecalculate(consumer: (UUID) -> Unit): AutoCloseable {
            consumers += consumer
            return AutoCloseable { consumers.remove(consumer) }
        }

        fun recalculate(uuid: UUID) = consumers.forEach { it(uuid) }
    }

    private fun adapter(lp: FakeLpSource = FakeLpSource()) =
        LuckPermsAdapter(lp, logger) to lp

    @Test
    fun permissionCheckDelegatesToLpCachedData() {
        val (adapter, lp) = adapter()
        lp.users[uuid] = FakeUser(granted = true)
        assertTrue(adapter.hasPermission(uuid, ServerCorePermissions.ADMIN).awaitCompleted())

        lp.users[uuid] = FakeUser(granted = false)
        assertFalse(adapter.hasPermission(uuid, ServerCorePermissions.ADMIN).awaitCompleted())
    }

    @Test
    fun permissionCheckFailsClosedOnLookupFailure() {
        val (adapter, lp) = adapter()
        lp.loadFailure = IllegalStateException("storage down")
        assertFalse(adapter.hasPermission(uuid, ServerCorePermissions.ADMIN).awaitCompleted())
    }

    @Test
    fun permissionCheckFailsClosedWhenLpEvaluationThrows() {
        val (adapter, lp) = adapter()
        lp.users[uuid] = ThrowingUser()
        assertFalse(adapter.hasPermission(uuid, ServerCorePermissions.ADMIN).awaitCompleted())
    }

    @Test
    fun groupsReturnEffectiveNamesAndPrimary() {
        val (adapter, lp) = adapter()
        lp.users[uuid] = FakeUser(groups = setOf("default", "moderator"), primary = "moderator")
        val groups = adapter.groups(uuid).awaitCompleted()
        assertEquals(setOf("default", "moderator"), groups.groups)
        assertEquals("moderator", groups.primary)
    }

    @Test
    fun topRankResolvesTheProtectionOrdering() {
        val (adapter, lp) = adapter()
        lp.users[uuid] = FakeUser(groups = setOf("default", "admin", "owner"))
        assertEquals(RankGroup.OWNER, adapter.topRank(uuid).awaitCompleted())

        lp.users[uuid] = FakeUser(groups = setOf("default", "helper"))
        assertEquals(RankGroup.HELPER, adapter.topRank(uuid).awaitCompleted())
    }

    @Test
    fun topRankIsNullOutsideTheLadder() {
        val (adapter, lp) = adapter()
        lp.users[uuid] = FakeUser(groups = setOf("vip", "builder"))
        assertNull(adapter.topRank(uuid).awaitCompleted())
    }

    @Test
    fun addGroupAppliesTheGroupNodeThroughLp() {
        val (adapter, lp) = adapter()
        val result = adapter.addGroup(uuid, RankGroup.MODERATOR).awaitCompleted()
        assertEquals(GroupMutationResult.APPLIED, result)
        assertEquals(listOf("+moderator"), (lp.users.getValue(uuid) as FakeUser).mutations)
        assertEquals(setOf("moderator"), lp.users.getValue(uuid).groupNames())
    }

    @Test
    fun removeGroupDropsTheGroupNode() {
        val (adapter, lp) = adapter()
        lp.users[uuid] = FakeUser(groups = setOf("moderator"))
        assertEquals(GroupMutationResult.APPLIED, adapter.removeGroup(uuid, RankGroup.MODERATOR).awaitCompleted())
        assertTrue(lp.users.getValue(uuid).groupNames().isEmpty())
    }

    @Test
    fun mutationOnAMissingNodeIsRejected() {
        val (adapter, lp) = adapter()
        assertEquals(GroupMutationResult.REJECTED, adapter.removeGroup(uuid, RankGroup.ADMIN).awaitCompleted())
        assertTrue((lp.users.getValue(uuid) as FakeUser).mutations.isNotEmpty())
    }

    @Test
    fun recalculationEventsReachTheBridge() {
        val (adapter, lp) = adapter()
        val received = mutableListOf<UUID>()
        val subscription = adapter.recalculationBridge.subscribe { received += it }

        lp.recalculate(uuid)
        assertEquals(listOf(uuid), received)

        subscription.close()
        lp.recalculate(uuid)
        assertEquals(1, received.size)
    }

    @Test
    fun shutdownDetachesTheRecalculationListener() {
        val (adapter, lp) = adapter()
        val received = mutableListOf<UUID>()
        adapter.recalculationBridge.subscribe { received += it }

        adapter.shutdown()
        lp.recalculate(uuid)
        assertTrue(received.isEmpty())
    }
}
