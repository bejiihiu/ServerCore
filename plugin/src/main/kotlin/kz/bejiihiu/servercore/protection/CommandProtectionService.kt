package kz.bejiihiu.servercore.protection

import java.util.logging.Logger

/**
 * owns the alias-expanded blocked set. the matcher is rebuilt whenever the
 * alias picture may have changed (server reload re-runs the commands
 * lifecycle event); between rebuilds every consumer reads one immutable
 * snapshot, so the region-thread event handlers never touch a half-built
 * structure.
 */
class CommandProtectionService(
    private val config: ProtectionConfig,
    private val aliasSource: () -> CommandTreeSnapshot,
    private val logger: Logger,
) {

    @Volatile
    private var matcher = buildMatcher()

    /** null when the line may run */
    fun match(commandLine: String): BlockedCommandMatcher.Match? = matcher.match(commandLine)

    /** rebuild the alias expansion against the current command tree */
    fun refreshAliases() {
        matcher = buildMatcher()
        logger.info(
            "command protection armed: ${config.blockedCommands.size} blocked commands, " +
                "${matcher.deniedNames().size} names after alias expansion",
        )
    }

    /** entries of the client command list whose bare name is denied */
    fun isDeniedForTabComplete(entry: String): Boolean {
        val head = BlockedCommandMatcher.parseHead(entry) ?: return false
        return head in matcher.deniedNames()
    }

    private fun buildMatcher(): BlockedCommandMatcher =
        BlockedCommandMatcher(config.blockedCommands, config.blockedDatapackCommands, aliasSource)
}
