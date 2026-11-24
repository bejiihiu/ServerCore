package kz.bejiihiu.servercore.disguise

import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * folia-correct scheduler hop: the snapshot is taken on the target player's
 * own entity scheduler, so no entity state is ever read off-region. the
 * callback then runs wherever the caller likes (packet writes are netty-safe).
 *
 * shutdown nuance: folia silently refuses scheduler tasks for a disabled
 * plugin (run returns null) — the reload restore path would never emit its
 * reveals and clients would keep ghost mobs / missing players. so a refused
 * task falls back to an inline capture wrapped in a catch, the same teardown
 * compromise the observer restore already makes: a slightly stale position
 * read off-scheduler beats a disguise that survives the reload forever.
 */
class FoliaDisguiseScheduler(
    private val plugin: Plugin,
) : DisguiseScheduler {

    override fun run(target: UUID, block: (TargetEntity) -> Unit) {
        val player = plugin.server.getPlayer(target) ?: return
        val task = player.scheduler.run(plugin, { _ -> block(player.toTargetEntity()) }, null)
        if (task == null) runCatching { block(player.toTargetEntity()) }
    }
}

internal fun org.bukkit.entity.Player.toTargetEntity(): TargetEntity {
    val loc = location
    return TargetEntity(entityId, DisguiseSnapshot(loc.x, loc.y, loc.z, loc.yaw, loc.pitch))
}
