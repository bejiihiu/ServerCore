package kz.bejiihiu.servercore.protection

import java.util.logging.Logger
import org.bukkit.configuration.file.FileConfiguration

/**
 * protection knobs from config.yml. the blocked list ships with the full
 * baseline in config.yml; if an admin deletes the key entirely the baseline
 * from code applies, an explicitly empty list disables the block.
 */
data class ProtectionConfig(
    val enabled: Boolean,
    /** state-changing administrative commands, denied for every game account */
    val blockedCommands: List<String>,
    /** commands that hand execution to datapack code — a function can smuggle
     *  any blocked command inside, so the invocation paths are denied too */
    val blockedDatapackCommands: List<String>,
    val enforceGamemode: Boolean,
    /** 0 = off */
    val gamemodeSweepIntervalSeconds: Long,
    /** 0 = off, otherwise loaded chunks are scanned for armed command blocks */
    val commandBlockAuditIntervalSeconds: Long,
    /** clear a found blocked command from the block itself, not only log it */
    val commandBlockNeutralize: Boolean,
    /** keep blocked commands out of the client command list (display only) */
    val hideFromTabComplete: Boolean,
) {
    companion object {

        /** baseline of administrative commands denied for players, ops included */
        val DEFAULT_BLOCKED_COMMANDS = listOf(
            "op", "deop", "fill", "clone", "setblock", "summon", "gamemode",
            "tp", "teleport", "give", "item", "execute", "effect", "attribute",
            "kill", "difficulty", "time", "weather", "gamerule", "worldborder",
            "stop", "reload",
        )

        val DEFAULT_BLOCKED_DATAPACK_COMMANDS = listOf("function", "schedule", "datapack")

        fun load(config: FileConfiguration, logger: Logger): ProtectionConfig {
            val blocked = if (config.isList("protection.blocked-commands")) {
                config.getStringList("protection.blocked-commands")
            } else {
                DEFAULT_BLOCKED_COMMANDS.also {
                    logger.warning("protection.blocked-commands missing from config, using the built-in baseline")
                }
            }
            val datapack = if (config.isList("protection.blocked-datapack-commands")) {
                config.getStringList("protection.blocked-datapack-commands")
            } else {
                DEFAULT_BLOCKED_DATAPACK_COMMANDS
            }
            return ProtectionConfig(
                enabled = config.getBoolean("protection.enabled", true),
                blockedCommands = normalize(blocked),
                blockedDatapackCommands = normalize(datapack),
                enforceGamemode = config.getBoolean("protection.enforce-gamemode", true),
                gamemodeSweepIntervalSeconds = config.getLong("protection.gamemode-sweep-interval-seconds", 30L),
                commandBlockAuditIntervalSeconds = config.getLong("protection.command-block-audit-interval-seconds", 600L),
                commandBlockNeutralize = config.getBoolean("protection.command-block-neutralize", false),
                hideFromTabComplete = config.getBoolean("protection.hide-from-tab-complete", true),
            )
        }

        /** reuses the matcher's normalization so config and input always agree */
        private fun normalize(names: List<String>): List<String> =
            names.asSequence()
                .map(BlockedCommandMatcher::normalize)
                .filter { it.isNotEmpty() }
                .toList()
    }
}
