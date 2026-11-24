package kz.bejiihiu.servercore.guest

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player

/** bukkit-facing side of the snapshot: capture and re-apply, both run on the player's own region thread */

fun captureSnapshot(player: Player): PlayerSnapshot = PlayerSnapshot(
    world = player.world.name,
    x = player.location.x,
    y = player.location.y,
    z = player.location.z,
    yaw = player.location.yaw.toDouble(),
    pitch = player.location.pitch.toDouble(),
    gameMode = player.gameMode.name,
    flying = player.isFlying,
    flySpeed = player.flySpeed.toDouble(),
    walkSpeed = player.walkSpeed.toDouble(),
)

fun PlayerSnapshot.toLocation(world: World?): Location? = world?.let {
    Location(it, x, y, z, yaw.toFloat(), pitch.toFloat())
}

/** restores game mode, speeds and flight state, never the position (that is a teleport decision) */
fun PlayerSnapshot.applyTo(player: Player) {
    val mode = parseGameMode(gameMode)
    player.gameMode = mode
    player.flySpeed = flySpeed.toFloat().coerceIn(-1.0f, 1.0f)
    player.walkSpeed = walkSpeed.toFloat().coerceIn(-1.0f, 1.0f)
    val flightCapable = mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR
    player.allowFlight = flying || flightCapable
    player.isFlying = flying && player.allowFlight
}

fun parseGameMode(name: String): GameMode =
    GameMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: GameMode.SURVIVAL
