package kz.bejiihiu.servercore.integration.luckperms

import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * narrow seam over the luckperms surface. [LuckPermsAdapter] codes against
 * these two interfaces, so its logic is testable over fakes while the real
 * wrapper ([LuckPermsApiSource]) stays a thin, typechecked shell around the
 * actual lp api.
 */
interface LpUserSource {

    /** loads (or creates) the lp user for [uuid], async */
    fun loadUser(uuid: UUID): CompletableFuture<LpUserView>

    /**
     * applies [mutation] to the user's own node map inside lp's
     * modifyUser transaction; the result reports whether the mutation
     * actually changed anything.
     */
    fun modifyUser(uuid: UUID, mutation: (LpUserView) -> Boolean): CompletableFuture<Boolean>

    /**
     * subscribes to lp user-data recalculations, receives the player uuid.
     * the returned handle unsubscribes when closed.
     */
    fun subscribeRecalculate(consumer: (UUID) -> Unit): AutoCloseable
}

/** the slice of a loaded lp user this integration actually needs */
interface LpUserView {

    /** permission check through lp's own cached data (lp owns the cache) */
    fun permissionValue(node: String): Boolean

    /** effective group names, inheritance already resolved by lp */
    fun groupNames(): Set<String>

    /** primary group name, null when lp reports none */
    fun primaryGroup(): String?

    /** adds the group.<name> node to the user's own nodes, true when applied */
    fun addGroupNode(group: String): Boolean

    /** removes the group.<name> node from the user's own nodes, true when applied */
    fun removeGroupNode(group: String): Boolean
}
