package kz.bejiihiu.servercore.websocket

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kz.bejiihiu.servercore.util.AtomicJsonFile
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.concurrent.Executor

/** one parked frame waiting for the backend link to come back */
@Serializable
data class ParkedFrame(
    val id: String,
    /** idempotency key: sessionId+type for guest events, the envelope id otherwise */
    val key: String,
    val type: String,
    /** byte-exact signed wire form, replayed as-is so the peer can dedupe by id */
    val wire: String,
    val enqueuedAt: Long,
)

/**
 * the durable outbox: guest lifecycle frames park here until the backend
 * acknowledges them, and the file survives a plugin crash. one pending frame
 * per idempotency key (sessionId+type for GUEST_*), so a redelivered
 * GUEST_STARTED replaces its pending twin instead of piling up — the event is
 * never lost and never duplicated.
 */
class DurableOutbox(
    file: Path,
    private val io: Executor,
    maxEntries: Int = DEFAULT_MAX,
) {
    private val store = AtomicJsonFile(file, ListSerializer(ParkedFrame.serializer()), io, Json {
        ignoreUnknownKeys = true
    })
    private val maxEntries = maxEntries.coerceAtLeast(1)

    private val entries = LinkedHashMap<String, ParkedFrame>()

    /** loads parked frames from disk at startup, order preserved */
    fun load() {
        synchronized(entries) {
            entries.clear()
            store.read().orEmpty().forEach { entries.remove(it.key); entries[it.key] = it }
        }
    }

    /** upserts by idempotency key; bounded, the oldest key is the first to go */
    fun park(entry: ParkedFrame) {
        synchronized(entries) {
            entries.remove(entry.key)
            entries[entry.key] = entry
            while (entries.size > maxEntries) {
                entries.remove(entries.keys.first())
            }
        }
        persistAsync()
    }

    /** removes an entry once its frame was acknowledged by the backend */
    fun unpark(id: String) {
        val removed = synchronized(entries) { entries.entries.removeIf { it.value.id == id } }
        if (removed) persistAsync()
    }

    fun snapshot(): List<ParkedFrame> = synchronized(entries) { entries.values.toList() }

    fun size(): Int = synchronized(entries) { entries.size }

    /**
     * persists whatever is in memory right now. every write serializes against
     * the lock and re-reads the live state, so a slow queued write can never
     * clobber newer content with an older snapshot.
     */
    private fun persistNow() {
        store.writeNow(synchronized(entries) { entries.values.toList() })
    }

    /** async on the io executor, failures are logged not thrown */
    private fun persistAsync() {
        val task = Runnable { persistNow() }
        // same story as the json file itself: a rejected task (io pool already
        // shut down during disable) falls back to an inline write, a park must
        // never throw at the caller that is unwinding a player quit
        runCatching { io.execute(task) }.onFailure { task.run() }
    }

    /** blocking flush for shutdown, so the last park is never lost */
    fun flushNow() {
        persistNow()
    }

    companion object {
        const val DEFAULT_MAX = 500
    }
}
