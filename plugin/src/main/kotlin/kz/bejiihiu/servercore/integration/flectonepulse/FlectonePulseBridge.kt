package kz.bejiihiu.servercore.integration.flectonepulse

import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kz.bejiihiu.servercore.ServerCorePlugin
import net.flectone.pulse.FlectonePulse
import net.flectone.pulse.FlectonePulseAPI
import net.flectone.pulse.module.integration.telegram.TelegramModule
import net.flectone.pulse.platform.registry.ListenerRegistry
import net.flectone.pulse.service.FPlayerService
import net.flectone.pulse.service.ModerationService
import org.bukkit.Bukkit

/**
 * the soft-depend bridge to flectonepulse.
 *
 * boundaries, in one place so nobody has to dig for them:
 * - servercore only consumes the public api artifact (net.flectone.pulse:api),
 *   never flectonepulse internals, and never shades fp classes
 * - servercore never issues moderation actions: no /ban /mute /warn /kick of
 *   its own, no chat pipeline, no prefixes — it reads punishment state and
 *   subscribes to events, nothing else
 * - telegram announcements belong to whoever owns the telegram integration:
 *   when fp's telegram module is on, servercore suppresses its own
 *
 * lifecycle: [start] runs on plugin enable (fp loads before us per the
 * paper-plugin.yml dependency). detection is plugin-manager presence + a
 * runtime class probe of the api surface; if the artifact is older/newer than
 * the compiled-against version the bridge degrades with a precise message.
 * fp's injector may still be spinning up when we probe, so connecting retries
 * a few times on the async scheduler before giving up.
 *
 * teardown: [stop] runs from onDisable and is the first thing to go. the
 * fp-side listener cannot be unregistered (see [FlectonePulseListenerSlot]:
 * fp has `registerPermanent` and a global `unregisterAll`, no unregister-one),
 * so the bridge is what gets switched off instead. after [stop] the surviving
 * listener finds an empty slot, every callback returns before touching
 * anything, and nothing is scheduled on a plugin folia already retired.
 */
class FlectonePulseBridge(
    private val plugin: ServerCorePlugin,
    private val config: FlectonePulseConfig,
    private val logger: Logger,
) : FlectonePulseListenerTarget {

    enum class State { NOT_PRESENT, INCOMPATIBLE, CONNECTING, CONNECTED }

    private data class Services(
        val players: FPlayerService,
        val moderation: ModerationService,
        val registry: ListenerRegistry,
        val telegramEnabled: () -> Boolean?,
    )

    companion object {
        private const val PLUGIN_NAME = "FlectonePulse"
        private const val READY_ATTEMPTS = 5
        private const val READY_RETRY_SECONDS = 2L

        // one listener for the whole classloader: fp keeps it forever and has
        // no unregister-one api, so a second registration would double every
        // callback. a re-enabled plugin re-binds this same slot instead
        private val listenerSlot = FlectonePulseListenerSlot()
    }

    private val state = AtomicReference(State.NOT_PRESENT)

    // one cache for the whole bridge lifetime: the permanent listener is bound
    // to it and keeps invalidating it across reloads — a per-connect cache
    // would strand every load/quit invalidation in an orphaned object while
    // the live query serves stale answers until the ttl runs out
    private val cache: PunishmentCache = PunishmentCache(config.punishmentCacheTtlMillis)

    // false until [start], false again after [stop]. fp can still call the
    // surviving listener and anyone can still call [revalidate]; neither may
    // schedule work for a plugin that is on its way out
    @Volatile
    private var live = false

    @Volatile
    private var query: PunishmentQuery = PunishmentQuery.None

    @Volatile
    private var suppression: NotificationSuppression = NotificationSuppression.None

    fun isAvailable(): Boolean = state.get() == State.CONNECTED

    fun currentState(): State = state.get()

    /** the punishment lookup, empty until fp is actually connected */
    fun punishmentQuery(): PunishmentQuery = query

    /**
     * value for SERVER_STATUS frames: true = backend should not announce this
     * state to telegram (fp does), false = announce, null = unknown/no fp —
     * backend decides with its defaults.
     */
    fun telegramSuppressed(): Boolean? =
        if (isAvailable()) suppression.shouldSuppressTelegram() else null

    /** entry point, safe to call from onEnable */
    fun start() {
        live = true
        val manager = Bukkit.getPluginManager()
        val fp = manager.getPlugin(PLUGIN_NAME)
        if (fp == null || !manager.isPluginEnabled(PLUGIN_NAME)) {
            logger.info("flectonepulse not present, running without moderation lookups")
            return
        }

        val missing = FlectonePulseApiCompat.firstMissingClass(::classPresent)
        if (missing != null) {
            state.set(State.INCOMPATIBLE)
            logger.severe(
                "flectonepulse api artifact does not match the expected " +
                    "${FlectonePulseApiCompat.TARGET_VERSION} surface (missing $missing), " +
                    "integration disabled, align the server's flectonepulse with the api this plugin was built against",
            )
            return
        }

        state.set(State.CONNECTING)
        logger.info("flectonepulse ${fp.pluginMeta.version} detected, wiring the api bridge")
        attemptConnect(READY_ATTEMPTS)
    }

    /** called from fp lifecycle events (reload end, late enable) on fp threads */
    fun revalidate() {
        if (!live) return
        val current = state.get()
        if (current != State.CONNECTED && current != State.CONNECTING) return
        // the listener outlived the reload: re-resolve the services, but the
        // registration check keeps the dispatch table free of duplicates
        attemptConnect(READY_ATTEMPTS)
    }

    /**
     * called from onDisable, before the rest of the graph unwinds.
     *
     * the fp-side listener stays in fp's registry — there is no way to take it
     * out — so instead this drops everything the listener could reach. from
     * here on a callback is a no-op, the punishment lookup answers null and
     * nothing is scheduled against the retired plugin. the one thing this
     * cannot do is unload the listener's classes: fp holds that object until
     * fp itself is recreated, and it stays inert until then.
     */
    fun stop() {
        live = false
        listenerSlot.unbind(this)
        query = PunishmentQuery.None
        suppression = NotificationSuppression.None
        state.set(State.NOT_PRESENT)
        logger.fine("flectonepulse bridge stopped, the permanent listener is inert from here on")
    }

    override fun invalidatePlayer(uuid: UUID) {
        cache.invalidate(uuid)
    }

    override fun afterFpRestarted() {
        cache.invalidateAll()
        revalidate()
    }

    private fun classPresent(name: String): Boolean =
        runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess

    private fun attemptConnect(attemptsLeft: Int) {
        if (!live) return
        val fp = runCatching { FlectonePulseAPI.getInstance() }.getOrNull()
        val ready = fp != null && runCatching { fp.isReady }.getOrDefault(false)
        if (fp == null || !ready) {
            if (attemptsLeft <= 0) {
                logger.severe(
                    "flectonepulse is installed but its api never became ready, " +
                        "moderation lookups stay off, check the flectonepulse log",
                )
                return
            }
            // a disable may land between the check above and this call: folia
            // then refuses to schedule for the retired plugin, and letting that
            // throw would take down fp's event thread. losing the retry is fine
            runCatching {
                plugin.server.asyncScheduler.runDelayed(
                    plugin,
                    { _ -> attemptConnect(attemptsLeft - 1) },
                    READY_RETRY_SECONDS,
                    TimeUnit.SECONDS,
                )
            }.onFailure {
                logger.warning("flectonepulse readiness retry could not be scheduled: ${it.message}")
            }
            return
        }
        connect(fp)
    }

    private fun connect(fp: FlectonePulse) {
        if (!live) return
        val services = runCatching {
            Services(
                players = fp.get(FPlayerService::class.java),
                moderation = fp.get(ModerationService::class.java),
                registry = fp.get(ListenerRegistry::class.java),
                telegramEnabled = telegramProbe(fp),
            )
        }.getOrElse { error ->
            logger.severe("flectonepulse api services unavailable: ${error.message}, integration disabled")
            return
        }

        query = FlectonePulsePunishmentQuery(services.players, services.moderation, cache, logger)
        suppression = FlectonePulseNotificationSuppression(disableTg = { config.disableTg }, telegramModuleEnabled = services.telegramEnabled)

        listenerSlot.bind(this)
        runCatching { listenerSlot.ensureRegistered(services.registry) }
            .onFailure {
                logger.warning(
                    "flectonepulse listener registration failed, cached punishment answers " +
                        "will only expire by ttl: ${it.message}",
                )
            }

        state.set(State.CONNECTED)
        logger.info("flectonepulse bridge connected: read-only punishment lookups on")
    }

    /**
     * reads fp's own telegram module state through the public api. null means
     * the answer is unknown right now (injector not ready, api shape changed)
     * and the caller must assume "notify" — silence is the worse failure.
     */
    private fun telegramProbe(fp: FlectonePulse): () -> Boolean? = {
        runCatching { fp.get(TelegramModule::class.java).config().enable() }
            .onFailure { logger.warning("flectonepulse telegram probe failed, assuming notifications are on: ${it.message}") }
            .getOrNull()
    }
}
