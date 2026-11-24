package kz.bejiihiu.servercore.integration.luckperms

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

/**
 * the config-driven stand-in for [PermissionSource] used when LuckPerms is
 * absent (it is a soft-depend). reads the static permission map from
 * config.yml, never touches the network, never crashes. group mutations are
 * loudly rejected: without lp there is nothing to persist them into.
 */
class StaticPermissionFallback(
    private val config: LuckPermsFallbackConfig,
    private val logger: Logger,
) : PermissionSource {

    override val sourceName: String = "static config fallback"

    override val recalculationBridge: PermissionRecalculationBridge = PermissionRecalculationBridge()

    override fun hasPermission(uuid: UUID, node: String): CompletableFuture<Boolean> =
        CompletableFuture.completedFuture(matches(uuid, node))

    override fun groups(uuid: UUID): CompletableFuture<PlayerGroups> {
        val group = config.groups[uuid] ?: config.defaultGroup
        return CompletableFuture.completedFuture(PlayerGroups(primary = group, groups = setOf(group)))
    }

    override fun topRank(uuid: UUID): CompletableFuture<RankGroup?> =
        groups(uuid).thenApply { RankGroup.highestOf(it.groups) }

    override fun addGroup(uuid: UUID, group: RankGroup): CompletableFuture<GroupMutationResult> {
        logger.warning("group change to '${group.configName}' for $uuid ignored: LuckPerms is not installed, the static fallback cannot persist group membership")
        return CompletableFuture.completedFuture(GroupMutationResult.REJECTED)
    }

    override fun removeGroup(uuid: UUID, group: RankGroup): CompletableFuture<GroupMutationResult> {
        logger.warning("group change to '${group.configName}' for $uuid ignored: LuckPerms is not installed, the static fallback cannot persist group membership")
        return CompletableFuture.completedFuture(GroupMutationResult.REJECTED)
    }

    override fun shutdown() {
        // nothing to release
    }

    /**
     * a node is granted when it appears in the player's own list or the
     * everyone list. matching: exact node, "*" (everything), or a prefix
     * wildcard "servercore.*" matching "servercore" and everything below it.
     */
    private fun matches(uuid: UUID, node: String): Boolean {
        val pool = config.playerPermissions[uuid].orEmpty() + config.everyonePermissions
        return pool.any { granted -> granted == "*" || granted == node || prefixMatch(granted, node) }
    }

    private fun prefixMatch(granted: String, node: String): Boolean {
        if (!granted.endsWith(".*")) return false
        val prefix = granted.removeSuffix(".*")
        return node == prefix || node.startsWith("$prefix.")
    }
}
