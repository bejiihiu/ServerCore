package kz.bejiihiu.servercore.spec

import java.util.UUID
import kz.bejiihiu.servercore.spectator.SpecSessionReader
import kz.bejiihiu.servercore.util.I18n
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.luckperms.api.LuckPerms
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * glue between the command, the permission backends and the session machine.
 * resolves the target and permissions (luckperms lookups may block on storage,
 * so they run on the async scheduler), then hands an agreed-upon transition to
 * the coordinator, which owns conflicts, phases and the audit trail.
 */
class SpecService(
    private val plugin: Plugin,
    private val config: SpecConfig,
    private val policy: SpecPermissionPolicy,
    private val luckPerms: LuckPerms?,
    private val coordinator: SessionCoordinator,
    private val effects: FoliaSpecEffects,
    private val journal: SpecRestoreJournal,
    private val reports: SpecSessionRegistry?,
    private val i18n: I18n,
) {

    private val mini = MiniMessage.miniMessage()

    /**
     * tab-completion gate, never blocks: a known level lets the command show,
     * an unknown answer falls back to the vanilla probe, and if both are
     * silent the command stays hidden (execution would deny anyway).
     */
    fun visibleFor(uuid: UUID): Boolean {
        if (!config.enabled) return false
        if (policy.levelOf(uuid) != null) return true
        return vanillaProbeLevel(uuid) != null
    }

    private fun vanillaProbeLevel(uuid: UUID): Int? {
        val player = plugin.server.getPlayer(uuid) ?: return null
        return if (player.hasPermission(config.permission.specPermission)) config.permission.fallbackLevelIndex else null
    }

    /** /spec <player>: the full pipeline, replies to the admin via [reply] */
    fun start(admin: Player, targetName: String, reply: (Component) -> Unit) {
        if (!config.enabled) {
            reply(text("disabled"))
            return
        }
        val current = coordinator.activeSession(admin.uniqueId)
        if (current != null) {
            reply(text("busy-admin", "target" to current.targetName))
            return
        }
        val targetPlayer = plugin.server.getPlayerExact(targetName)
        if (targetPlayer == null || !targetPlayer.isOnline) {
            reply(text("no-target", "target" to targetName))
            return
        }
        if (targetPlayer.uniqueId == admin.uniqueId) {
            reply(text("self"))
            return
        }

        // luckperms storage access is not for game threads; the async scheduler
        // resolves both levels, the entity scheduler does every world touch
        plugin.server.asyncScheduler.runNow(plugin, { _ ->
            val adminLevel = resolveLevel(admin.uniqueId)
            if (adminLevel == null) {
                reports?.denied(
                    admin.uniqueId, admin.name,
                    targetPlayer.uniqueId, targetPlayer.name,
                    "no access level resolved",
                )
                reply(text("denied-permission"))
                return@runNow
            }
            val targetLevel = resolveLevel(targetPlayer.uniqueId)
            if (!policy.canObserve(adminLevel, targetLevel)) {
                reports?.denied(
                    admin.uniqueId, admin.name,
                    targetPlayer.uniqueId, targetPlayer.name,
                    "target access level ${targetLevel?.let { policy.describe(it) } ?: "regular"} outranks ${policy.describe(adminLevel)}",
                )
                reply(text("denied-level", "target" to targetPlayer.name))
                return@runNow
            }

            val target = ObservationTarget(
                uuid = targetPlayer.uniqueId,
                name = targetPlayer.name,
                levelIndex = targetLevel,
            )
            when (coordinator.start(admin.uniqueId, admin.name, target, onSettled = { settled ->
                if (settled) {
                    reply(text("started", "target" to target.name))
                } else {
                    reply(text("aborted", "target" to target.name))
                }
            })) {
                StartOutcome.AdminBusy -> reply(
                    text("busy-admin", "target" to (coordinator.activeSession(admin.uniqueId)?.targetName ?: target.name)),
                )
                StartOutcome.TargetBusy -> reply(text("busy-target", "target" to target.name))
                StartOutcome.Started -> { /* the settled callback owns the reply */ }
            }
        })
    }

    /** /spec without arguments: leave the current session */
    fun exit(admin: Player, reply: (Component) -> Unit) {
        when (coordinator.exit(admin.uniqueId, ExitReason.COMMAND)) {
            ExitOutcome.Ended -> reply(text("exited"))
            ExitOutcome.NoSession -> reply(text("not-observing"))
        }
    }

    /** quit hook: ends the admin's own session or the session watching this player */
    fun onQuit(playerId: UUID) {
        coordinator.handleQuit(playerId)
    }

    /** read-side seam for the live inventory viewer: who is this admin watching right now */
    fun readerFor(adminId: UUID): SpecSessionReader = CoordinatorSessionReader(coordinator, adminId)

    /** join hook: replays a pending restore for an admin who left mid-session */
    fun onJoin(player: Player) {
        val record = journal.pending(player.uniqueId) ?: return
        journal.remove(player.uniqueId)
        effects.replayRestore(player, record)
    }

    /** best-effort sweep on plugin disable; live restores, the rest goes to the journal */
    fun shutdown() {
        coordinator.shutdownAll()
        journal.shutdown()
    }

    private fun resolveLevel(uuid: UUID): Int? {
        // non-blocking pass first (loaded luckperms user, vanilla fallback),
        // then the resolving luckperms pass which may wait on storage
        policy.levelOf(uuid)?.let { return it }
        if (luckPerms != null && config.permission.useLuckPerms) {
            return policy.levelOf(uuid, resolving = true)
        }
        return null
    }

    private fun text(key: String, vararg placeholders: Pair<String, String>): Component {
        val raw = i18n.text("spec.$key", *placeholders)
        return mini.deserialize(raw)
    }
}
