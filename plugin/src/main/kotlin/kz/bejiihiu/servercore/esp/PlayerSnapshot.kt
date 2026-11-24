package kz.bejiihiu.servercore.esp

import org.bukkit.GameMode
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** tiny value type so the pure layers never touch bukkit vectors */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    fun distanceSquaredTo(other: Vec3): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }
}

/**
 * immutable per-player facts captured on the player's own entity scheduler
 * (the only thread allowed to read player state on folia). everything the
 * engine needs travels in this record, so no engine code path ever pokes a
 * Player object from the wrong thread.
 */
data class PlayerSnapshot(
    val id: UUID,
    val world: String,
    val eye: Vec3,
    val feet: Vec3,
    /** world vertical bounds, captured here so ray building never reads world state */
    val minY: Int,
    val maxY: Int,
    val sneaking: Boolean,
    val spectator: Boolean,
    val dead: Boolean,
) {
    companion object {
        /** must be called on the region thread owning [player] */
        fun capture(player: Player): PlayerSnapshot {
            val eye = player.eyeLocation
            val feet = player.location
            return PlayerSnapshot(
                id = player.uniqueId,
                world = player.world.name,
                eye = Vec3(eye.x, eye.y, eye.z),
                feet = Vec3(feet.x, feet.y, feet.z),
                minY = player.world.minHeight,
                // highest y a block can actually occupy
                maxY = player.world.maxHeight - 1,
                sneaking = player.isSneaking,
                spectator = player.gameMode == GameMode.SPECTATOR,
                dead = player.isDead,
            )
        }
    }
}
