package kz.bejiihiu.servercore.integration.flectonepulse

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
 */
class FlectonePulseBridge(
    private val plugin: ServerCorePlugin,
    private val config: FlectonePulseConfig,
    private val logger: Logger,
) {

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

        // registerPermanent listeners survive fp reloads and there is no
        // unregister-one api, so a disable/enable cycle in the same
        // classloader must not stack duplicates
        private val listenerRegistered = AtomicBoolean(false)
    }

    private val state = AtomicReference(State.NOT_PRESENT)
    private var reason: String = "not detected"

    // one cache for the whole bridge lifetime: the permanent listener is
    // registered exactly once with this instance and keeps invalidating it
    // across reloads — a per-connect cache would strand every load/quit
    // invalidation in an orphaned object while the live query serves stale
    // answers until the ttl runs out
    private val cache: PunishmentCache = PunishmentCache(config.punishmentCacheTtlMillis)

    @Volatile
    private var query: PunishmentQuery = PunishmentQuery.None

    @Volatile
    private var suppression: NotificationSuppression = NotificationSuppression.None

    fun isAvailable(): Boolean = state.get() == State.CONNECTED

    fun currentState(): State = state.get()

    /** why the bridge is not connected, for logs and diagnostics */
    fun currentReason(): String = reason

    /** the punishment lookup, empty until fp is actually connected */
    fun punishmentQuery(): PunishmentQuery = query

    /** the telegram de-duplication check notification senders consult */
    fun notificationSuppression(): NotificationSuppression = suppression

    /**
     * value for SERVER_STATUS frames: true = backend should not announce this
     * state to telegram (fp does), false = announce, null = unknown/no fp —
     * backend decides with its defaults.
     */
    fun telegramSuppressed(): Boolean? =
        if (isAvailable()) suppression.shouldSuppressTelegram() else null

    /** entry point, safe to call from onEnable */
    fun start() {
        val manager = Bukkit.getPluginManager()
        val fp = manager.getPlugin(PLUGIN_NAME)
        if (fp == null || !manager.isPluginEnabled(PLUGIN_NAME)) {
            reason = "plugin not installed, integration off"
            logger.info("flectonepulse not present, running without moderation lookups")
            return
        }

        val missing = FlectonePulseApiCompat.firstMissingClass(::classPresent)
        if (missing != null) {
            state.set(State.INCOMPATIBLE)
            reason = "api surface mismatch: $missing missing"
            logger.severe(
                "flectonepulse api artifact does not match the expected " +
                    "${FlectonePulseApiCompat.TARGET_VERSION} surface (missing $missing) — " +
                    "integration disabled, align the server's flectonepulse with the api this plugin was built against",
            )
            return
        }

        state.set(State.CONNECTING)
        reason = "waiting for the fp injector"
        logger.info("flectonepulse ${fp.pluginMeta.version} detected, wiring the api bridge")
        attemptConnect(reRegister = true, attemptsLeft = READY_ATTEMPTS)
    }

    /** called from fp lifecycle events (reload end, late enable) on fp threads */
    fun revalidate() {
        val current = state.get()
        if (current != State.CONNECTED && current != State.CONNECTING) return
        // injector was rebuilt by the reload: re-resolve everything, but the
        // permanent listener already survives, so never re-register it
        attemptConnect(reRegister = false, attemptsLeft = READY_ATTEMPTS)
    }

    private fun classPresent(name: String): Boolean =
        runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess

    private fun attemptConnect(reRegister: Boolean, attemptsLeft: Int) {
        val fp = runCatching { FlectonePulseAPI.getInstance() }.getOrNull()
        val ready = fp != null && runCatching { fp.isReady }.getOrDefault(false)
        if (fp == null || !ready) {
            if (attemptsLeft <= 0) {
                reason = "fp injector not ready after $READY_ATTEMPTS attempts"
                logger.severe(
                    "flectonepulse is installed but its api never became ready — " +
                        "moderation lookups stay off, check the flectonepulse log",
                )
                return
            }
            plugin.server.asyncScheduler.runDelayed(
                plugin,
                { _ -> attemptConnect(reRegister, attemptsLeft - 1) },
                READY_RETRY_SECONDS,
                TimeUnit.SECONDS,
            )
            return
        }
        connect(fp, reRegister)
    }

    private fun connect(fp: FlectonePulse, reRegister: Boolean) {
        val services = runCatching {
            Services(
                players = fp.get(FPlayerService::class.java),
                moderation = fp.get(ModerationService::class.java),
                registry = fp.get(ListenerRegistry::class.java),
                telegramEnabled = telegramProbe(fp),
            )
        }.getOrElse { error ->
            reason = "service resolution failed: ${error.message}"
            logger.severe("flectonepulse api services unavailable: ${error.message} — integration disabled")
            return
        }

        query = FlectonePulsePunishmentQuery(services.players, services.moderation, cache, logger)
        suppression = FlectonePulseNotificationSuppression(disableTg = { config.disableTg }, telegramModuleEnabled = services.telegramEnabled)

        if (reRegister && listenerRegistered.compareAndSet(false, true)) {
            services.registry.registerPermanent(FlectonePulseStateListener(cache) { revalidate() })
        }

        state.set(State.CONNECTED)
        reason = "connected"
        logger.info("flectonepulse bridge connected: read-only punishment lookups on")
    }

    /**
     * reads fp's own telegram module state through the public api. null means
     * the answer is unknown right now (injector not ready, api shape changed)
     * and the caller must assume "notify" — silence is the worse failure.
     */
    private fun telegramProbe(fp: FlectonePulse): () -> Boolean? = {
        runCatching { fp.get(TelegramModule::class.java).config().enable() }
            .onFailure { logger.fine("flectonepulse telegram probe failed: ${it.message}") }
            .getOrNull()
    }
}
