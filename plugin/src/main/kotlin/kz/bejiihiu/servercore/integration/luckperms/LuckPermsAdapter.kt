package kz.bejiihiu.servercore.integration.luckperms

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

/**
 * luckperms-backed [PermissionSource].
 *
 * async-first: every query goes out as a [CompletableFuture] straight from
 * the lp api and completes on lp's threads. nothing is cached here — lp owns
 * the cache (cached data, user loader), we only relay. failure policy:
 * permission checks fail closed (deny) with a logged warning, group reads
 * complete exceptionally so callers can decide how to surface the failure.
 */
class LuckPermsAdapter(
    private val lp: LpUserSource,
    private val logger: Logger,
) : PermissionSource {

    override val sourceName: String = "luckperms api"

    override val recalculationBridge: PermissionRecalculationBridge = PermissionRecalculationBridge()

    private val recalcSubscription: AutoCloseable =
        lp.subscribeRecalculate { uuid -> recalculationBridge.fire(uuid) }

    override fun hasPermission(uuid: UUID, node: String): CompletableFuture<Boolean> =
        lp.loadUser(uuid).handle { user, error ->
            // the whole check is fail-closed: lookup failure or a throw from
            // the lp-side evaluation both land on deny, never propagate —
            // an exceptional future would be "unknown", not "denied"
            when {
                error != null -> {
                    logger.warning("permission check failed for $uuid / '$node': ${rootMessage(error)} — denying")
                    false
                }
                else -> try {
                    user.permissionValue(node)
                } catch (t: Exception) {
                    logger.warning("permission evaluation failed for $uuid / '$node': ${rootMessage(t)} — denying")
                    false
                }
            }
        }

    override fun groups(uuid: UUID): CompletableFuture<PlayerGroups> =
        lp.loadUser(uuid).thenApply { user ->
            PlayerGroups(
                primary = user.primaryGroup(),
                groups = user.groupNames(),
            )
        }

    override fun topRank(uuid: UUID): CompletableFuture<RankGroup?> =
        groups(uuid).thenApply { RankGroup.highestOf(it.groups) }

    override fun addGroup(uuid: UUID, group: RankGroup): CompletableFuture<GroupMutationResult> =
        mutate(uuid) { it.addGroupNode(group.configName) }

    override fun removeGroup(uuid: UUID, group: RankGroup): CompletableFuture<GroupMutationResult> =
        mutate(uuid) { it.removeGroupNode(group.configName) }

    override fun shutdown() {
        runCatching { recalcSubscription.close() }
    }

    private fun mutate(uuid: UUID, mutation: (LpUserView) -> Boolean): CompletableFuture<GroupMutationResult> =
        lp.modifyUser(uuid, mutation).thenApply { applied ->
            if (applied) GroupMutationResult.APPLIED else GroupMutationResult.REJECTED
        }

    private fun rootMessage(error: Throwable): String {
        var cause: Throwable = error
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        return cause.message ?: cause.javaClass.simpleName
    }
}
