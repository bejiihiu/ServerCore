package kz.bejiihiu.servercore.guest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kz.bejiihiu.servercore.util.AtomicJsonFile

@Serializable
private data class CounterFile(
    val version: Int = 1,
    val counts: Map<String, Int> = emptyMap(),
)

/**
 * durable counter of guest sessions per player. survives restarts on purpose —
 * otherwise a quick rejoin cycle would reset the budget. the uuid here is not
 * an identity guarantee (offline-mode server, nicknames are forgeable), it is
 * only the local accounting key; the backend telegram binding is the actual
 * authority and can override anything this counter decided.
 */
class GuestSessionCounter(path: Path, io: Executor) {

    private val json = Json { ignoreUnknownKeys = true }
    private val file = AtomicJsonFile(path, CounterFile.serializer(), io, json)
    private val counts = ConcurrentHashMap<UUID, Int>()

    /** blocking, startup only */
    fun load() {
        file.read()?.counts?.forEach { (key, value) ->
            runCatching { UUID.fromString(key) }.getOrNull()?.let { uuid -> counts[uuid] = value }
        }
    }

    fun count(uuid: UUID): Int = counts.getOrDefault(uuid, 0)

    /** counts one more guest session for the player, persists asynchronously */
    fun increment(uuid: UUID): Int {
        val next = counts.merge(uuid, 1, Int::plus) ?: 1
        flush()
        return next
    }

    /** authorization reached: the guest budget starts over */
    fun reset(uuid: UUID) {
        if (counts.remove(uuid) != null) flush()
    }

    /** final synchronous flush for onDisable */
    fun shutdown() {
        file.writeNow(CounterFile(counts = counts.mapKeys { (uuid, _) -> uuid.toString() }))
    }

    private fun flush() {
        file.write(CounterFile(counts = counts.mapKeys { (uuid, _) -> uuid.toString() }))
    }
}
