package kz.bejiihiu.servercore.integration.luckperms

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import net.luckperms.api.LuckPerms
import net.luckperms.api.event.user.UserDataRecalculateEvent
import net.luckperms.api.model.data.DataMutateResult
import net.luckperms.api.model.user.User
import net.luckperms.api.node.Node

/**
 * the real luckperms-backed implementation of [LpUserSource]. deliberately
 * thin: every call forwards to the lp api, nothing is cached or re-implemented
 * here — lp's own cached data and loaders are the authority.
 */
class LuckPermsApiSource(private val api: LuckPerms) : LpUserSource {

    override fun loadUser(uuid: UUID): CompletableFuture<LpUserView> =
        api.userManager.loadUser(uuid).thenApply(::LpApiUser)

    override fun modifyUser(uuid: UUID, mutation: (LpUserView) -> Boolean): CompletableFuture<Boolean> {
        val applied = AtomicBoolean(false)
        return api.userManager.modifyUser(uuid) { user ->
            if (mutation(LpApiUser(user))) applied.set(true)
        }.thenApply { applied.get() }
    }

    override fun subscribeRecalculate(consumer: (UUID) -> Unit): AutoCloseable {
        // any user data recalculation is a superset of permission recalcs and
        // covers meta changes too, which is what invalidation seams want
        val subscription = api.eventBus.subscribe(UserDataRecalculateEvent::class.java) { event ->
            consumer(event.user.uniqueId)
        }
        return AutoCloseable { subscription.close() }
    }

    private class LpApiUser(private val user: User) : LpUserView {

        override fun permissionValue(node: String): Boolean =
            user.cachedData.getPermissionData().checkPermission(node).asBoolean()

        override fun groupNames(): Set<String> =
            user.getInheritedGroups(user.queryOptions).mapTo(mutableSetOf()) { it.name }

        override fun primaryGroup(): String? =
            user.primaryGroup?.trim()?.takeIf { it.isNotEmpty() }

        override fun addGroupNode(group: String): Boolean =
            user.data().add(Node.builder(groupNode(group)).build()) == DataMutateResult.SUCCESS

        override fun removeGroupNode(group: String): Boolean =
            user.data().remove(Node.builder(groupNode(group)).build()) == DataMutateResult.SUCCESS

        // the standard lp idiom: a user is in a group when they hold group.<name>
        private fun groupNode(group: String) = "group.$group"
    }
}
