package kz.bejiihiu.servercore.integration.flectonepulse

/**
 * telegram de-duplication seam. flectonepulse ships its own telegram module,
 * and when that one is active it is the one announcing events — servercore
 * must stay out of telegram or every restart hits the chat twice.
 *
 * every servercore notification sender consults this before pushing anything
 * towards telegram (the plugin side tags SERVER_STATUS frames, the backend
 * bot honours the tag).
 */
interface NotificationSuppression {

    /**
     * true = servercore's telegram notifications must be suppressed this
     * round, flectonepulse (or an explicit config decision) owns the channel.
     */
    fun shouldSuppressTelegram(): Boolean

    /** used when flectonepulse is not there at all: nothing to dedupe against */
    object None : NotificationSuppression {
        override fun shouldSuppressTelegram(): Boolean = false
    }
}

/**
 * the decision logic, kept pure for tests.
 *
 * @param disableTg servercore config flag (flectonepulse.disable-tg): the
 *   admin's declaration that fp is the telegram authority on this server
 * @param telegramModuleEnabled fp-side probe, true when fp's telegram module
 *   is enabled in its own config; null when the probe cannot answer (fp
 *   absent, injector not ready, api shape changed) — unknown means never
 *   suppress, a silent admin chat is worse than a doubled message
 */
data class SuppressionDecision(val suppress: Boolean, val source: String)

class FlectonePulseNotificationSuppression(
    private val disableTg: () -> Boolean,
    private val telegramModuleEnabled: () -> Boolean?,
) : NotificationSuppression {

    fun decision(): SuppressionDecision {
        val configured = disableTg()
        val fpEnabled = telegramModuleEnabled()
        return when {
            !configured -> SuppressionDecision(false, "config: servercore owns its announcements")
            fpEnabled == true -> SuppressionDecision(true, "flectonepulse: telegram module active")
            fpEnabled == null -> SuppressionDecision(false, "flectonepulse: telegram state unknown, notifying")
            else -> SuppressionDecision(false, "flectonepulse: telegram module off")
        }
    }

    override fun shouldSuppressTelegram(): Boolean = decision().suppress
}
