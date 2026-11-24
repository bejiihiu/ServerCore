package kz.bejiihiu.servercore.integration.flectonepulse

import java.util.logging.Logger
import org.bukkit.configuration.file.FileConfiguration

/**
 * servercore-side knobs for the flectonepulse integration.
 *
 * moderation and chat belong to flectonepulse — servercore only reads its
 * public api artifact and never issues bans/mutes/warns/kicks or touches the
 * chat pipeline. the one thing that needs a knob here is telegram
 * de-duplication: flectonepulse can announce events through its own telegram
 * module, and two systems announcing the same restart would be noise.
 */
data class FlectonePulseConfig(
    /**
     * master switch for telegram de-duplication. true (the recommended
     * default) means: whenever flectonepulse's own telegram module is active,
     * servercore keeps its ops notifications out of telegram so nothing is
     * announced twice. false means servercore announces regardless.
     */
    val disableTg: Boolean,
    /** how long a read-only punishment lookup result stays cached */
    val punishmentCacheTtlMillis: Long,
) {
    companion object {
        fun load(config: FileConfiguration, logger: Logger): FlectonePulseConfig {
            val ttlSeconds = config.getLong("flectonepulse.punishment-cache-ttl-seconds", 30L).coerceIn(1L, 3600L)
            return FlectonePulseConfig(
                disableTg = config.getBoolean("flectonepulse.disable-tg", true),
                punishmentCacheTtlMillis = ttlSeconds * 1000L,
            )
        }
    }
}
