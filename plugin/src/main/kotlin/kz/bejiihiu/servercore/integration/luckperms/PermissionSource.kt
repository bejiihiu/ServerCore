package kz.bejiihiu.servercore.integration.luckperms

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import net.luckperms.api.LuckPermsProvider

/** what a group mutation ended up as */
enum class GroupMutationResult { APPLIED, REJECTED }

/** a player's luckperms groups as seen through the api */
data class PlayerGroups(
    val primary: String?,
    /** effective group names, inheritance already resolved */
    val groups: Set<String>,
)

/**
 * the full permission surface the plugin consumes, async-first.
 * implemented twice: over the luckperms api and over the static config
 * fallback used when luckperms is absent.
 */
interface PermissionSource : PermissionCheck {

    /** what is backing the checks, for startup logging */
    val sourceName: String

    /** fires when luckperms recalculates a player's permission data */
    val recalculationBridge: PermissionRecalculationBridge

    /** loads a player's groups; completes exceptionally on lookup failure */
    fun groups(uuid: UUID): CompletableFuture<PlayerGroups>

    /** the player's effective rank per the protection ordering, null when outside the ladder */
    fun topRank(uuid: UUID): CompletableFuture<RankGroup?>

    /** adds a player to a group (lp-side group.<name> node) */
    fun addGroup(uuid: UUID, group: RankGroup): CompletableFuture<GroupMutationResult>

    /** removes a player from a group */
    fun removeGroup(uuid: UUID, group: RankGroup): CompletableFuture<GroupMutationResult>

    /** releases any luckperms handles (event subscriptions); safe to call twice */
    fun shutdown()
}

object PermissionSourceFactory {

    /**
     * picks the backing implementation: luckperms when it is installed and
     * loaded, the static config fallback otherwise. never throws — a missing
     * soft-depend degrades to the fallback with a loud warning instead.
     */
    fun create(config: LuckPermsConfig, logger: Logger): PermissionSource {
        return try {
            val api = LuckPermsProvider.get()
            logger.info("permissions backed by the luckperms api")
            LuckPermsAdapter(LuckPermsApiSource(api), logger)
        } catch (t: Throwable) {
            // luckperms classes not on the classpath or the provider not
            // loaded yet — both mean "no lp on this server"
            logger.warning(
                "luckperms not found (${t.javaClass.simpleName}: ${t.message}), " +
                    "falling back to the static permission map in config.yml " +
                    "under luckperms.fallback; group management is disabled until luckperms is installed",
            )
            StaticPermissionFallback(config.fallback, logger)
        }
    }
}
