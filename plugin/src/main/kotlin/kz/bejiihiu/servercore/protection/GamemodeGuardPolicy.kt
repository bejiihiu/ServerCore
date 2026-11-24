package kz.bejiihiu.servercore.protection

import org.bukkit.GameMode
import org.bukkit.event.player.PlayerGameModeChangeEvent

/**
 * pure decisions for the gamemode guard, no bukkit state involved.
 *
 * survival is always fine. two vanilla flows are honored without a grant:
 * DEFAULT_GAMEMODE (the server normalizing a join against its own configured
 * default) and HARDCORE_DEATH (vanilla death handling — not relevant on a
 * survival server but cancelling it would glitch respawn). everything else
 * needs an explicit override registry grant: /gamemode typed by a player is
 * blocked at the command layer anyway, a plugin switching modes without a
 * grant is exactly the attack shape this guard exists for.
 */
object GamemodeGuardPolicy {

    fun shouldAllowChange(target: GameMode, cause: PlayerGameModeChangeEvent.Cause, sanctioned: Boolean): Boolean =
        target == GameMode.SURVIVAL ||
            cause == PlayerGameModeChangeEvent.Cause.DEFAULT_GAMEMODE ||
            cause == PlayerGameModeChangeEvent.Cause.HARDCORE_DEATH ||
            sanctioned

    /** drift found by the periodic sweep: sitting in a mode nobody sanctioned */
    fun shouldRevert(current: GameMode, sanctioned: Boolean): Boolean =
        current != GameMode.SURVIVAL && !sanctioned
}
