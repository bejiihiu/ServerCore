package kz.bejiihiu.servercore.ops

import org.bukkit.configuration.file.FileConfiguration
import java.time.ZoneId

/** pterodactyl panel settings; all empty = feature off, console stop fallback */
data class PterodactylConfig(
    val panelUrl: String,
    val serverId: String,
    /** name of the env var holding the client api key; the key never lives in the yaml */
    val apiKeyEnv: String,
) {
    fun isConfigured(): Boolean = panelUrl.isNotBlank() && serverId.isNotBlank() && apiKeyEnv.isNotBlank()
}

data class UpdateConfig(
    val enabled: Boolean,
    val repo: String,
    val checkIntervalMinutes: Long,
    val autoRestart: Boolean,
    val githubTokenEnv: String,
)

data class RestartConfig(
    val pterodactyl: PterodactylConfig,
    val dailyTimes: List<RestartTime>,
    val graceSeconds: Int,
    val locale: String,
    val zone: ZoneId,
)

data class OpsConfig(val update: UpdateConfig, val restart: RestartConfig) {

    companion object {
        const val DEFAULT_ZONE_ID = "Europe/Moscow"

        /**
         * reads the ops: section of config.yml. invalid daily times are
         * skipped with a log line, an unusable zone falls back to
         * europe/moscow (product default) instead of crashing the plugin.
         */
        fun load(config: FileConfiguration, logger: java.util.logging.Logger): OpsConfig {
            val update = UpdateConfig(
                enabled = config.getBoolean("ops.update.enabled", true),
                repo = config.getString("ops.update.repo", "bejiihiu/ServerCore") ?: "bejiihiu/ServerCore",
                checkIntervalMinutes = (config.getLong("ops.update.check-interval-minutes", 30L)).coerceAtLeast(1L),
                autoRestart = config.getBoolean("ops.update.auto-restart", true),
                githubTokenEnv = config.getString("ops.update.github-token-env", "GITHUB_TOKEN") ?: "",
            )

            val pterodactyl = PterodactylConfig(
                panelUrl = config.getString("ops.restart.pterodactyl.panel-url", "") ?: "",
                serverId = config.getString("ops.restart.pterodactyl.server-id", "") ?: "",
                apiKeyEnv = config.getString("ops.restart.pterodactyl.api-key-env", "") ?: "",
            )

            val zone = try {
                ZoneId.of(config.getString("ops.restart.timezone", DEFAULT_ZONE_ID) ?: DEFAULT_ZONE_ID)
            } catch (_: Exception) {
                logger.warning("[ops] unknown timezone in config, using $DEFAULT_ZONE_ID")
                ZoneId.of(DEFAULT_ZONE_ID)
            }

            val times = (config.getStringList("ops.restart.daily-times"))
                .mapNotNull { raw ->
                    val parsed = RestartTime.parse(raw)
                    if (parsed == null) logger.warning("[ops] bad daily restart time '$raw', skipped")
                    parsed
                }

            val restart = RestartConfig(
                pterodactyl = pterodactyl,
                dailyTimes = times,
                graceSeconds = (config.getInt("ops.restart.grace-seconds", 60)).coerceIn(0, 600),
                locale = if (config.getString("ops.restart.locale", "ru") == "en") "en" else "ru",
                zone = zone,
            )

            return OpsConfig(update, restart)
        }
    }
}
