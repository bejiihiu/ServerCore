package kz.bejiihiu.servercore.websocket

/**
 * receiver-side dedupe for redelivered frames. the backend re-sends an
 * unacknowledged push after reconnecting with a fresh envelope id, so the
 * stable identity is the message content, not the id. a redelivered frame is
 * still acked (the sender needs closure) but never processed twice.
 */
class InboundDedupe(
    private val ttlMs: Long,
    private val maxKeys: Int = 5_000,
) {
    private val seen = LinkedHashMap<String, Long>()

    /** true when this key was already accepted inside the ttl window */
    fun isDuplicate(key: String, now: Long): Boolean {
        synchronized(seen) {
            purge(now)
            if (seen.putIfAbsent(key, now) != null) return true
            return false
        }
    }

    private fun purge(now: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > ttlMs) iterator.remove()
        }
        // safety valve for a pathological burst of unique frames
        while (seen.size > maxKeys) {
            seen.remove(seen.keys.first())
        }
    }
}
