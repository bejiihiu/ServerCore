package kz.bejiihiu.servercore.integration.flectonepulse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * telegram de-duplication: fp's telegram module being active is the only
 * reason to stay quiet, and an unknown state must never silence servercore.
 */
class NotificationSuppressionTest {

    private fun suppression(disableTg: Boolean, telegramEnabled: Boolean?) =
        FlectonePulseNotificationSuppression({ disableTg }, { telegramEnabled })

    @Test
    fun `fp telegram module active suppresses servercore notifications`() {
        val decision = suppression(disableTg = true, telegramEnabled = true).decision()
        assertTrue(decision.suppress)
        assertTrue(decision.source.contains("flectonepulse"))
        assertTrue(suppression(true, true).shouldSuppressTelegram())
    }

    @Test
    fun `fp telegram module off means servercore announces`() {
        assertFalse(suppression(disableTg = true, telegramEnabled = false).shouldSuppressTelegram())
    }

    @Test
    fun `unknown fp telegram state never silences servercore`() {
        // silence is the worse failure: a doubled message beats a missing one
        val decision = suppression(disableTg = true, telegramEnabled = null).decision()
        assertFalse(decision.suppress)
        assertTrue(decision.source.contains("unknown"))
    }

    @Test
    fun `config opt-out wins over an active fp telegram module`() {
        assertFalse(suppression(disableTg = false, telegramEnabled = true).shouldSuppressTelegram())
    }

    @Test
    fun `without flectonepulse there is nothing to suppress`() {
        assertFalse(NotificationSuppression.None.shouldSuppressTelegram())
    }

    @Test
    fun `decision names its source for diagnostics`() {
        assertEquals(
            "flectonepulse: telegram module active",
            suppression(true, true).decision().source,
        )
        assertEquals(
            "config: servercore owns its announcements",
            suppression(false, true).decision().source,
        )
    }
}
