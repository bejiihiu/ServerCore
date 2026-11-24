package kz.bejiihiu.servercore.disguise

import kz.bejiihiu.servercore.esp.ViewState

/**
 * the disguise layer speaks the one canonical view state from the esp module.
 * historically this package carried a signature-identical local copy; the
 * merge made the esp definition canonical and this alias keeps the local
 * naming (EntityViewState) without a second enum in play.
 */
typealias EntityViewState = ViewState

/** what the target stood like when the disguise scheduler captured it */
data class DisguiseSnapshot(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
)
