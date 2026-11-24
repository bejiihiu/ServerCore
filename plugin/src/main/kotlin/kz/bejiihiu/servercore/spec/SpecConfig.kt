package kz.bejiihiu.servercore.spec

import java.util.logging.Logger
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration

/**
 * permission settings for the observation command. the level list is ordered
 * highest first: the earlier a group appears, the more access its members have.
 */
data class SpecPermissionConfig(
    /** when true the LuckPerms api is the permission backend, vanilla checks are the fallback */
    val useLuckPerms: Boolean,
    /** group names, highest access first. membership in any of them grants that level */
    val levels: List<String>,
    /** the standalone permission node that also grants access (moderators per product decision) */
    val specPermission: String,
    /** level index granted by [specPermission] alone when no listed group matches */
    val fallbackLevelIndex: Int,
)

data class SpecAuditConfig(
    /** audit file path, relative to the plugin data folder */
    val path: String,
    /** rotate the file once it grows past this, bytes */
    val maxFileBytes: Long,
    /** how many rotated files to keep around */
    val maxKept: Int,
)

data class SpecConfig(
    val enabled: Boolean,
    /** live target inventory viewing is wired in a later phase, the toggle already exists */
    val liveInventory: Boolean,
    /** write every session start/stop/denial to the audit file */
    val logToAudit: Boolean,
    val permission: SpecPermissionConfig,
    val audit: SpecAuditConfig,
) {
    companion object {
        fun load(file: FileConfiguration, logger: Logger): SpecConfig {
            val spec = file.getConfigurationSection("spec")
            fun bool(name: String, default: Boolean) = spec?.getBoolean(name, default) ?: default

            val levels = spec?.getStringList("permission.levels")
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
                .ifEmpty { listOf("owner", "admin", "moderator", "helper") }
                .distinct()

            val fallbackName = spec?.getString("permission.fallback-level", "")?.trim()?.lowercase().orEmpty()
            val fallbackLevelIndex = levels.indexOf(fallbackName).takeIf { it >= 0 }
                // standalone spec permission holders land on the lowest level by default
                ?: (levels.size - 1).coerceAtLeast(0)

            val audit = auditFrom(spec?.getConfigurationSection("audit"))

            return SpecConfig(
                enabled = bool("enabled", true),
                liveInventory = bool("live-inventory", true),
                logToAudit = bool("log-to-audit", true),
                permission = SpecPermissionConfig(
                    useLuckPerms = bool("permission.use-luckperms", true),
                    levels = levels,
                    specPermission = spec?.getString("permission.spec-permission", "servercore.spec")
                        ?.trim()?.ifEmpty { "servercore.spec" } ?: "servercore.spec",
                    fallbackLevelIndex = fallbackLevelIndex,
                ),
                audit = audit,
            )
        }

        private fun auditFrom(section: ConfigurationSection?): SpecAuditConfig {
            val path = section?.getString("path", "audit/spec-audit.jsonl")
                ?.trim()?.ifEmpty { "audit/spec-audit.jsonl" } ?: "audit/spec-audit.jsonl"
            val maxBytes = section?.getLong("max-file-bytes", 5L * 1024 * 1024)?.coerceIn(64L * 1024, 1024L * 1024 * 1024)
                ?: 5L * 1024 * 1024
            val maxKept = section?.getInt("max-kept", 10)?.coerceIn(1, 100) ?: 10
            return SpecAuditConfig(path = path, maxFileBytes = maxBytes, maxKept = maxKept)
        }
    }
}
