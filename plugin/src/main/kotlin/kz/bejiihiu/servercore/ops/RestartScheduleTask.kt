package kz.bejiihiu.servercore.ops

import org.bukkit.plugin.java.JavaPlugin
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.logging.Logger

/**
 * daily restart schedule: a global region scheduler task wakes up every
 * [CHECK_PERIOD_TICKS] ticks, computes the next restart instant in the
 * configured zone (zone rules, dst-proof) and fires the graceful flow when
 * the wall clock reaches it.
 *
 * the fire guard makes sure one due window triggers exactly one restart even
 * if the task ticks several times inside it.
 */
class RestartScheduleTask(
    private val plugin: JavaPlugin,
    private val times: List<RestartTime>,
    private val zone: ZoneId,
    private val restarter: GracefulRestarter,
    private val logger: Logger,
) {

    private var next: ZonedDateTime? = null
    private var task: io.papermc.paper.threadedregions.scheduler.ScheduledTask? = null

    fun start() {
        if (times.isEmpty()) {
            logger.info("[ops] no daily restart times configured, schedule off")
            return
        }
        next = RestartPlanner.nextAfter(times, ZonedDateTime.now(zone), zone)
        logger.info("[ops] daily restarts at ${times.joinToString { "${it.hour}:${"%02d".format(it.minute)}" }} ($zone), next: $next")

        task = plugin.server.globalRegionScheduler.runAtFixedRate(
            plugin,
            { _ -> tick() },
            CHECK_PERIOD_TICKS,
            CHECK_PERIOD_TICKS,
        )
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    private fun tick() {
        val planned = next ?: RestartPlanner.nextAfter(times, ZonedDateTime.now(zone), zone) ?: return
        val now = ZonedDateTime.now(zone)
        if (!RestartPlanner.isDue(now, planned)) return

        // grab the window: set the pointer past it right away
        next = RestartPlanner.nextAfter(times, planned, zone)
        restarter.begin(RestartReason.SCHEDULED)
    }

    private companion object {
        /** check every 15 seconds - good enough for minute-precision cron times */
        const val CHECK_PERIOD_TICKS = 300L
    }
}
