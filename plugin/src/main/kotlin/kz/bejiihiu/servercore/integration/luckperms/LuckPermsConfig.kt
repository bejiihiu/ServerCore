package kz.bejiihiu.servercore.integration.luckperms

import java.util.UUID
import java.util.logging.Logger
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration

/**
 * static stand-in used only when LuckPerms is not installed on the server.
 * config-driven so the plugin still boots and admins keep access; never a
 * replacement for real group management.
 */
data class LuckPermsFallbackConfig(
    /** group handed to every player the static map knows nothing about */
    val defaultGroup: String,
    /** uuid -> group name, feeds rank ordering in fallback mode */
    val groups: Map<UUID, String>,
    /** permission nodes granted to every player */
    val everyonePermissions: List<String>,
    /** uuid -> permission nodes, exact nodes or prefix wildcards ("servercore.*") */
    val playerPermissions: Map<UUID, List<String>>,
)

data class LuckPermsConfig(
    val fallback: LuckPermsFallbackConfig,
) {
    companion object {

        fun load(file: FileConfiguration, logger: Logger): LuckPermsConfig {
            val section = file.getConfigurationSection("luckperms")
            val fallbackSection: ConfigurationSection? = section?.getConfigurationSection("fallback")

            val groups = buildMap {
                fallbackSection?.getConfigurationSection("groups")?.getKeys(false)?.forEach { key ->
                    val uuid = runCatching { UUID.fromString(key) }.getOrNull()
                    if (uuid == null) {
                        logger.warning("luckperms.fallback.groups: '$key' is not a uuid, entry ignored")
                    } else {
                        val group = fallbackSection.getConfigurationSection("groups")?.getString(key, "")?.trim().orEmpty()
                        if (group.isEmpty()) {
                            logger.warning("luckperms.fallback.groups: '$key' has an empty group name, entry ignored")
                        } else {
                            put(uuid, group)
                        }
                    }
                }
            }

            val players = buildMap {
                fallbackSection?.getConfigurationSection("permissions.players")?.getKeys(false)?.forEach { key ->
                    val uuid = runCatching { UUID.fromString(key) }.getOrNull()
                    if (uuid == null) {
                        logger.warning("luckperms.fallback.permissions.players: '$key' is not a uuid, entry ignored")
                    } else {
                        put(uuid, fallbackSection.getConfigurationSection("permissions.players")?.getStringList(key).orEmpty())
                    }
                }
            }

            return LuckPermsConfig(
                fallback = LuckPermsFallbackConfig(
                    defaultGroup = fallbackSection?.getString("default-group", "default")?.trim()
                        ?.ifEmpty { "default" } ?: "default",
                    groups = groups,
                    everyonePermissions = fallbackSection?.getConfigurationSection("permissions")
                        ?.getStringList("everyone").orEmpty().filter { it.isNotBlank() },
                    playerPermissions = players.mapValues { (_, nodes) -> nodes.filter { it.isNotBlank() } },
                ),
            )
        }
    }
}
