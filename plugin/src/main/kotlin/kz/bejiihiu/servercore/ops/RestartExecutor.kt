package kz.bejiihiu.servercore.ops

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Logger

/**
 * how the server actually gets restarted.
 *
 * the plugin runs inside a pterodactyl container and CANNOT reliably restart
 * its own jvm process - the supported path is the panel's client api
 * (POST /api/client/servers/<id>/power, signal=restart). when the panel is
 * not configured we fall back to a plain console `stop`: pterodactyl restarts
 * the container automatically when the server has the auto-restart flag
 * enabled in the panel - that flag is REQUIRED for the fallback path to
 * behave like a restart, document it in the deployment notes.
 */
class RestartExecutor(
    private val plugin: JavaPlugin,
    private val pterodactyl: PterodactylConfig,
    private val logger: Logger,
) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /**
     * triggers the restart. called from a global region scheduler context;
     * the panel call itself is dispatched to a virtual thread so the game
     * loop never blocks on http.
     */
    fun requestRestart(reason: String) {
        if (pterodactyl.isConfigured()) {
            logger.info("[ops] restart requested ($reason) via pterodactyl panel")
            Thread.ofVirtual().name("servercore-panel-restart").start {
                val ok = try {
                    powerRestart()
                } catch (error: Exception) {
                    logger.warning("[ops] panel restart failed: ${error.message}")
                    false
                }
                if (!ok) {
                    // last resort: console stop. works only with panel auto-restart on.
                    logger.warning("[ops] falling back to console stop - requires panel auto-restart enabled")
                    consoleStop()
                }
            }
        } else {
            logger.info("[ops] restart requested ($reason): no panel configured, dispatching console stop (needs panel auto-restart enabled)")
            consoleStop()
        }
    }

    /** POST /api/client/servers/<id>/power {"signal":"restart"} */
    private fun powerRestart(): Boolean {
        val apiKey = System.getenv(pterodactyl.apiKeyEnv)
        if (apiKey.isNullOrBlank()) {
            logger.warning("[ops] env var ${pterodactyl.apiKeyEnv} is empty, cannot call the panel")
            return false
        }

        val base = pterodactyl.panelUrl.trimEnd('/')
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$base/api/client/servers/${pterodactyl.serverId}/power"))
            .timeout(Duration.ofSeconds(30))
            .setHeader("Authorization", "Bearer $apiKey")
            .setHeader("Accept", "application/json")
            .setHeader("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"signal":"restart"}"""))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            // 204 = accepted, 409 = a power action is already queued, which is
            // just as good for us
            204, 409 -> true
            else -> {
                logger.warning("[ops] panel answered http ${response.statusCode()}: ${response.body().take(200)}")
                false
            }
        }
    }

    /**
     * plain shutdown through the server console. MUST run on the global
     * region thread - that is the only place bukkit api dispatch is legal
     * under folia.
     */
    private fun consoleStop() {
        plugin.server.globalRegionScheduler.run(plugin) { _ ->
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop")
        }
    }
}
