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
 */
class AtomicJsonFile<T>(
    private val file: Path,
    private val serializer: KSerializer<T>,
    private val io: Executor,
    private val json: Json,
) {

    private val writeLock = ReentrantLock()

    /** blocking read for startup only, null when the file is missing or unreadable */
    fun read(): T? {
        if (!Files.exists(file)) return null
        return runCatching { json.decodeFromString(serializer, Files.readString(file, StandardCharsets.UTF_8)) }
            .onFailure { error ->
                System.err.println("servercore: cannot read ${file.fileName}, starting from a blank slate ($error)")
            }
            .getOrNull()
    }

    /** replaces the file content asynchronously, failures are logged not thrown */
    fun write(value: T) {
        io.execute {
            runCatching { writeBlocking(value) }.onFailure { error ->
                System.err.println("servercore: cannot write ${file.fileName}, change lost until next save ($error)")
            }
        }
    }

    /** blocking variant for shutdown, serializes against in-flight async writes */
    fun writeNow(value: T) {
        runCatching { writeBlocking(value) }.onFailure { error ->
            System.err.println("servercore: cannot write ${file.fileName}, change lost ($error)")
        }
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
