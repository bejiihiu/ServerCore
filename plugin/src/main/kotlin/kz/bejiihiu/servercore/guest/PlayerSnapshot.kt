package kz.bejiihiu.servercore.guest

import kotlinx.serialization.Serializable

/**
 * the original player state captured once when a guest session starts.
 * field names double as the wire format expected by the backend snapshot
 * schema, so renaming anything here breaks the protocol.
 */
@Serializable
data class PlayerSnapshot(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Double,
    val pitch: Double,
    val gameMode: String,
    val flying: Boolean,
    val flySpeed: Double,
    val walkSpeed: Double,
)
