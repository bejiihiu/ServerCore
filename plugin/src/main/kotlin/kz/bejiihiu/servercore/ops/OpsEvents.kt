package kz.bejiihiu.servercore.ops

import kotlinx.serialization.Serializable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger

/**
 * ops-level server lifecycle states, mirrored 1:1 with the backend wire
 * protocol taxonomy. travel inside SERVER_STATUS frames.
 */
enum class ServerState {
    STARTING,
    RUNNING,
    RESTARTING,
    STOPPING,
    OFFLINE,
    UPDATE_READY,
}

/**
 * payload of a SERVER_STATUS frame. field names match the backend zod schema
 * exactly; kotlinx serialization produces the json the gateway validates.
 */
@Serializable
data class ServerStatusPayload(
    val onlinePlayers: Int,
    val maxPlayers: Int,
    val tps: Double? = null,
    val version: String? = null,
    val state: String? = null,
    val detail: String? = null,
)

/**
 * everything ops wants delivered over the websocket. the ws client module
 * registers its own sink here as soon as it merges; until then the bus logs,
 * so an update/restart never silently loses its announcement.
 */
fun interface OpsEventSink {
    fun publish(state: ServerState, detail: String?, payload: ServerStatusPayload)
}

/**
 * fan-out bus for SERVER_STATUS publications. reading the player counts has
 * to happen on a region-safe context - the providers are handed in by the
 * plugin and are expected to be called from a global scheduler tick, never
 * from arbitrary threads.
 */
class OpsEventBus(
    private val logger: Logger,
    private val onlinePlayers: () -> Int,
    private val maxPlayers: () -> Int,
    private val version: () -> String?,
) {

    private val sinks = CopyOnWriteArrayList<OpsEventSink>()

    fun register(sink: OpsEventSink) {
        sinks.addIfAbsent(sink)
    }

    /** never throws: a broken sink must not take the updater or restart down */
    fun publish(state: ServerState, detail: String? = null) {
        if (sinks.isEmpty()) {
            logger.info("[ops] state ${state.name} (no ws sink registered yet, event not delivered)")
            return
        }
        val payload = ServerStatusPayload(
            onlinePlayers = onlinePlayers(),
            maxPlayers = maxPlayers(),
            version = version(),
            state = state.name,
            detail = detail,
        )
        for (sink in sinks) {
            try {
                sink.publish(state, detail, payload)
            } catch (error: Exception) {
                logger.warning("[ops] event sink failed: ${error.message}")
            }
        }
    }
}
