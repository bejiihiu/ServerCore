package kz.bejiihiu.servercore.spec

import java.util.UUID
import java.util.concurrent.TimeUnit
import net.luckperms.api.LuckPerms
import net.luckperms.api.model.user.User
import net.luckperms.api.util.Tristate

/**
 * the one question every permission backend must answer for us: does this
 * player hold this exact node. null means "cannot say" (backend absent, user
 * not loaded) - the policy decides whether that means fall-through or deny.
 */
fun interface PermissionAdapter {
    fun checkNode(uuid: UUID, node: String): Boolean?
}

/**
 * luckperms backend. group membership is read through the implicitly granted
 * group.<name> node, which is the canonical way to test membership without
 * touching the group api. user lookups may hit luckperms storage, so the
 * resolving variant is only for async contexts.
 */
class LuckPermsAdapter(private val luckPerms: LuckPerms, private val loadTimeoutMs: Long = 3_000) : PermissionAdapter {

    override fun checkNode(uuid: UUID, node: String): Boolean? =
        luckPerms.userManager.getUser(uuid)?.let { fromUser(it, node) }

    /** resolving variant: falls back to a storage load, blocking - async threads only */
    fun checkNodeResolving(uuid: UUID, node: String): Boolean? {
        luckPerms.userManager.getUser(uuid)?.let { return fromUser(it, node) }
        val user = runCatching {
            luckPerms.userManager.loadUser(uuid).get(loadTimeoutMs, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return null
        return fromUser(user, node)
    }

    private fun fromUser(user: User, node: String): Boolean =
        when (user.cachedData.permissionData.checkPermission(node)) {
            Tristate.TRUE -> true
            else -> false
        }
}

/**
 * fallback backend: reads through the live player's permissible, which answers
 * both plain permission plugins and luckperms-granted nodes. offline players
 * are unanswerable, hence null.
 */
class VanillaAdapter(private val lookup: (UUID) -> org.bukkit.entity.Player?) : PermissionAdapter {

    override fun checkNode(uuid: UUID, node: String): Boolean? =
        lookup(uuid)?.hasPermission(node)
}

/**
 * decision logic on top of the backend chain:
 * - a player's access level is the highest listed group they belong to;
 * - holding the standalone spec permission without any listed group lands them
 *   on the configured fallback level;
 * - nobody may observe a player with strictly higher access.
 * the level list is highest-first, so lower index = more access.
 *
 * chain semantics: the first adapter with a definitive answer (no null node
 * checks) wins even when the answer is "no access"; null answers fall through
 * to the next adapter.
 */
class SpecPermissionPolicy(
    private val adapters: List<PermissionAdapter>,
    private val levels: List<String>,
    private val specPermission: String,
    private val fallbackLevelIndex: Int,
) {

    /** resolves the access level or null when no backend grants anything */
    fun levelOf(uuid: UUID, resolving: Boolean = false): Int? {
        for (adapter in adapters) {
            var unknown = false

            val groupLevel = levels.indexOfFirst { group ->
                when (val answer = if (resolving) adapter.resolving(uuid, "group.$group") else adapter.checkNode(uuid, "group.$group")) {
                    true -> true
                    false -> false
                    null -> { unknown = true; false }
                }
            }
            if (groupLevel >= 0) return groupLevel

            when (val spec = if (resolving) adapter.resolving(uuid, specPermission) else adapter.checkNode(uuid, specPermission)) {
                true -> return fallbackLevelIndex
                false -> {}
                null -> unknown = true
            }

            // every check came back false: this backend definitively denies
            if (!unknown) return null
        }
        return null
    }

    /** admin may observe the target when the target has no level or an equal/lower one */
    fun canObserve(adminLevel: Int, targetLevel: Int?): Boolean =
        targetLevel == null || adminLevel <= targetLevel

    /** human-readable level name for audit lines */
    fun describe(level: Int): String = levels.getOrNull(level) ?: "unknown"

    private fun PermissionAdapter.resolving(uuid: UUID, node: String): Boolean? =
        (this as? LuckPermsAdapter)?.checkNodeResolving(uuid, node) ?: checkNode(uuid, node)
}
