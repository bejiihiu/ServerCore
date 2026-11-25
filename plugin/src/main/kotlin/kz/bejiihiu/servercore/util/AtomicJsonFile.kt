package kz.bejiihiu.servercore.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.name

/**
 * small helper for durable json files: atomic temp-file + move writes, all io
 * pushed onto the given executor (virtual threads for us) so nothing here ever
 * blocks a region thread. reads are only meant for startup.
 *
 * durability rules: a failed write is retried a few times before it is given
 * up as lost (a quit racing a save must still land), a corrupt file is
 * quarantined at startup instead of boot-failing the plugin, and every write
 * carries a sequence number so a stale async flush can never land after a
 * newer one (a shutdown flush must not be rolled back by a queued write).
 */
class AtomicJsonFile<T>(
    private val file: Path,
    private val serializer: KSerializer<T>,
    private val io: Executor,
    private val json: Json,
    /** how many times an async write is retried before the change is declared lost */
    private val writeAttempts: Int = 3,
    /** linear backoff between write attempts, grows with the attempt number */
    private val retryDelayMs: Long = 100,
) {

    private val writeLock = ReentrantLock()
    private val sequence = java.util.concurrent.atomic.AtomicLong()

    /** newest sequence whose write was accepted, guarded by writeLock */
    private var newest = 0L

    /**
     * blocking read for startup only, null when the file is missing or
     * unreadable. an unreadable file is moved aside (quarantined) so a human
     * can inspect what went wrong, and the store starts from a blank slate —
     * a hand-edited or half-corrupted state file never boot-fails the plugin.
     */
    fun read(): T? {
        if (!Files.exists(file)) return null
        val parsed = runCatching { json.decodeFromString(serializer, Files.readString(file, StandardCharsets.UTF_8)) }
        parsed.getOrNull()?.let { return it }
        runCatching {
            Files.move(file, file.resolveSibling("${file.name}.corrupt-${System.currentTimeMillis()}"))
        }
        System.err.println("servercore: ${file.name} is unreadable, quarantined and reset (${parsed.exceptionOrNull()})")
        return null
    }

    /** replaces the file content asynchronously with retry, failures are logged not thrown */
    fun write(value: T) {
        val seq = sequence.incrementAndGet()
        val task = Runnable { writeVersioned(value, seq) }
        // an executor that is already shut down (the disable path shuts the io
        // pool down last) must not turn a save into an exception on the
        // caller's thread: persist inline instead, the write still lands
        runCatching { io.execute(task) }.onFailure { task.run() }
    }

    /** blocking variant for shutdown, serializes against in-flight async writes */
    fun writeNow(value: T) {
        writeVersioned(value, sequence.incrementAndGet())
    }

    /** writes unless a newer value already took over, older flushes never win */
    private fun writeVersioned(value: T, seq: Long) {
        writeLock.lock()
        try {
            if (seq < newest) return
            newest = seq
            writeWithRetry(value)
        } finally {
            writeLock.unlock()
        }
    }

    private fun writeWithRetry(value: T) {
        val attempts = writeAttempts.coerceAtLeast(1)
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            runCatching { writeBlocking(value) }
                .onSuccess { return }
                .onFailure { lastError = it }
            if (attempt < attempts - 1) runCatching { Thread.sleep(retryDelayMs * (attempt + 1)) }
        }
        System.err.println(
            "servercore: cannot write ${file.name} after $attempts attempt(s), change lost until next save ($lastError)",
        )
    }

    private fun writeBlocking(value: T) {
        writeLock.lock()
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.name + ".tmp")
            Files.writeString(tmp, json.encodeToString(serializer, value), StandardCharsets.UTF_8)
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                // some filesystems just refuse atomic moves, plain replace is fine
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            writeLock.unlock()
        }
    }
}
