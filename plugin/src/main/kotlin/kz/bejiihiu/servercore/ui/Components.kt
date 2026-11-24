package kz.bejiihiu.servercore.ui

import kz.bejiihiu.servercore.locale.LocaleService
import kz.bejiihiu.servercore.ui.UiScheduler.uiDelayed
import kz.bejiihiu.servercore.ui.UiScheduler.uiRepeat
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * the composed component layer. every component pulls its text from the
 * locale service and every surface decision from the theme; callers pass
 * keys and placeholder values, never raw copy.
 */
class UiKit(
    val plugin: Plugin,
    val locale: LocaleService,
) {

    /** headline surface: state changes and arrivals only, see the design doc */
    fun title(
        player: Player,
        titleKey: String,
        subtitleKey: String? = null,
        values: Map<String, String> = emptyMap(),
        stayMs: Long = 2600,
    ) {
        val t = locale.component(titleKey, values)
        val s = subtitleKey?.let { locale.component(it, values) } ?: Component.empty()
        UiFx.title(player, t, s, stayMs)
    }

    /** one transient actionbar line from a locale key */
    fun actionBar(player: Player, key: String, values: Map<String, String> = emptyMap()) {
        UiFx.actionBar(player, locale.component(key, values))
    }

    /**
     * a status card in chat: the locale template provides the content lines,
     * the theme provides rails and rules. use for anything a player may want
     * to scroll back to.
     */
    fun statusCard(player: Player, key: String, values: Map<String, String> = emptyMap()) {
        val lines = locale.lines(key, values).map { Theme.render(it) }
        UiFx.sendFrame(player, lines)
    }

    /** single-line system event ping, colored by kind */
    fun banner(player: Player, kind: BannerKind, key: String, values: Map<String, String> = emptyMap()) {
        val line = Component.text("${Theme.MARK} ", Theme.GOLD)
            .append(locale.component(key, values).colorIfAbsent(kind.color))
        player.sendMessage(line)
    }

    /** ambient identity surface, called on join and on locale change */
    fun tabList(player: Player) {
        UiFx.tabList(player, locale.component("tablist.header"), locale.component("tablist.footer"))
    }

    /** a reusable countdown driven by the player's entity scheduler */
    fun countdown(
        player: Player,
        seconds: Int,
        labelKey: String,
        extra: Map<String, String> = emptyMap(),
        tickSubtitleKey: String? = null,
        onDone: () -> Unit = {},
    ): Countdown = Countdown(this, player, seconds, labelKey, extra, tickSubtitleKey, onDone)

    /**
     * the guest arrival flow: stages the title and status card after join,
     * starts the auth prompt right behind them, returns the prompt handle so
     * callers can stop everything on logout or when a decision arrives.
     */
    fun guestWelcome(player: Player, session: Int, maxSessions: Int): AuthPrompt {
        val values = mapOf("n" to session.toString(), "max" to maxSessions.toString())
        player.uiDelayed(plugin, JOIN_STAGING_DELAY_TICKS) {
            title(player, "guest.welcome.title", "guest.welcome.subtitle", values, stayMs = 3200)
            statusCard(player, "guest.welcome.card", values)
        }
        val prompt = AuthPrompt(this, player, nextCode(), AUTH_WINDOW_SECONDS)
        prompt.start()
        return prompt
    }

    /**
     * the auth gate surface: card with the code once, bossbar countdown for
     * the confirmation window, pulsing actionbar reminder. the prompt stages
     * itself a beat after the welcome so the two cards do not collide.
     */
    fun authPrompt(player: Player, code: String, windowSeconds: Int): AuthPrompt {
        val prompt = AuthPrompt(this, player, code, windowSeconds)
        prompt.start()
        return prompt
    }

    private fun nextCode(): String =
        "%03d-%03d".format(RANDOM.nextInt(1000), RANDOM.nextInt(1000))

    companion object {
        // staging delay after join: let the world render before ui lands
        const val JOIN_STAGING_DELAY_TICKS = 40L

        // default confirmation window; the backend overrides per request
        const val AUTH_WINDOW_SECONDS = 300

        private val RANDOM = SecureRandom()
    }
}

/** banner color kinds mapped to theme semantics */
enum class BannerKind(val color: net.kyori.adventure.text.format.TextColor) {
    INFO(Theme.INFO),
    OK(Theme.OK),
    WARN(Theme.WARN),
    DANGER(Theme.DANGER),
}

/**
 * a live auth prompt handle. owns its countdown and pulse task; stop() is
 * safe from any thread and idempotent, so quit handlers can call it blindly.
 */
class AuthPrompt(
    private val kit: UiKit,
    private val player: Player,
    private val code: String,
    windowSeconds: Int,
) {
    private val countdown: Countdown = kit.countdown(
        player,
        windowSeconds,
        labelKey = "auth.window",
        extra = mapOf("code" to code),
        onDone = { expire() },
    )
    private var pulseTask: io.papermc.paper.threadedregions.scheduler.ScheduledTask? = null
    private val pulses = AtomicLong(0)
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start() {
        // stage a beat behind the welcome card so the two frames don't collide
        player.uiDelayed(kit.plugin, START_DELAY_TICKS) {
            kit.statusCard(player, "auth.prompt.card", mapOf("code" to code))
            countdown.start()
            pulseTask = player.uiRepeat(kit.plugin, 20L, PULSE_PERIOD_TICKS) { t -> pulse(t) }
        }
    }

    private fun pulse(t: io.papermc.paper.threadedregions.scheduler.ScheduledTask) {
        // a dozen glances at the bar is enough; the countdown carries it after
        if (pulses.incrementAndGet() > PULSE_LIMIT) {
            t.cancel()
            return
        }
        // the pulse: filled marker on even beats, hollow on odd ones
        val marker = if (pulses.get() % 2 == 0L) Theme.PULSE_A else Theme.PULSE_B
        player.sendActionBar(
            Component.text("$marker ", Theme.GOLD).append(kit.locale.component("auth.pulse", mapOf("code" to code))),
        )
    }

    private fun expire() {
        stop()
        kit.banner(player, BannerKind.DANGER, "auth.expired")
    }

    /** stops pulse and countdown, clears the bar; callable from any thread */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        pulseTask?.cancel()
        countdown.cancel()
    }

    companion object {
        const val PULSE_PERIOD_TICKS = 40L
        const val PULSE_LIMIT = 12L

        // the welcome title plays for ~3s; the prompt lands a beat after it
        // clears so no two headline surfaces ever overlap
        const val START_DELAY_TICKS = 120L
    }
}
