package kz.bejiihiu.servercore.spec

import org.bukkit.configuration.file.FileConfiguration
import java.util.logging.Logger

/**
 * local spec audit trail settings, from the `spec:` config section. the trail
 * is the plugin-side append-only jsonl journal of observation sessions; the
 * backend keeps its own copy in the audit_logs collection.
 */
data class SpecAuditConfig(
    /** master switch, mirrors the existing spec.log-to-audit key */
    val enabled: Boolean,
    /** directory for the jsonl files, relative to plugins/ServerCore/ */
    val directory: String,
    /** how long rotated files live on disk, days */
    val retentionDays: Int,
    /** audit rows allowed per single spec session, overflow is dropped */
    val maxEntriesPerSession: Int,
    /** bounded buffer size; a full buffer drops entries, it never blocks */
    val queueCapacity: Int,
) {
    companion object {
        fun load(config: FileConfiguration, logger: Logger): SpecAuditConfig {
            val enabled = config.getBoolean("spec.log-to-audit", true)
            val directory = config.getString("spec.audit.directory", "audit")!!.trim()
            var retentionDays = config.getInt("spec.audit.retention-days", 30)
            var maxEntriesPerSession = config.getInt("spec.audit.max-entries-per-session", 64)
            var queueCapacity = config.getInt("spec.audit.queue-capacity", 1024)

            if (retentionDays < 1) {
                logger.warning("spec.audit.retention-days < 1, using 30")
                retentionDays = 30
            }
            if (maxEntriesPerSession < 1) {
                logger.warning("spec.audit.max-entries-per-session < 1, using 64")
                maxEntriesPerSession = 64
            }
            if (queueCapacity < 16) {
                logger.warning("spec.audit.queue-capacity < 16, using 1024")
                queueCapacity = 1024
            }
            require(directory.isNotEmpty()) { "spec.audit.directory must not be empty" }
            require(!directory.contains("..")) { "spec.audit.directory must stay inside the plugin data folder" }

            return SpecAuditConfig(
                enabled = enabled,
                directory = directory,
                retentionDays = retentionDays,
                maxEntriesPerSession = maxEntriesPerSession,
                queueCapacity = queueCapacity,
            )
        }
    }
}
