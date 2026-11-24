package kz.bejiihiu.servercore.disguise

import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * folia-correct scheduler hop: the snapshot is taken on the target player's
 * own entity scheduler, so no entity state is ever read off-region. the
 * callback then runs wherever the caller likes (packet writes are netty-safe).
 */
class FoliaDisguiseScheduler(
    private val plugin: Plugin,
) : DisguiseScheduler {

    override fun run(target: UUID, block: (TargetEntity) -> Unit) {
        val player = plugin.server.getPlayer(target) ?: return
        player.scheduler.run(plugin, { _ -> block(player.toTargetEntity()) }, null)
    }
}

internal fun org.bukkit.entity.Player.toTargetEntity(): TargetEntity {
    val loc = location
    return TargetEntity(entityId, DisguiseSnapshot(loc.x, loc.y, loc.z, loc.yaw, loc.pitch))
}
