package kz.bejiihiu.servercore.util

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Serializable
private data class Sample(val name: String, val n: Int)

/**
 * durability rules of the json state files: temp+move atomicity, corrupt
 * files quarantine instead of boot-failing, and a blocked write retries
 * instead of silently dropping the change.
 */
class AtomicJsonFileTest {

    @TempDir
    lateinit var dir: Path

    private val json = Json { ignoreUnknownKeys = true }

    private fun newFile(name: String, attempts: Int = 3, delayMs: Long = 100) =
        AtomicJsonFile(dir.resolve(name), Sample.serializer(), Runnable::run, json, attempts, delayMs)

    @Test
    fun `write then read survives a reload`() {
        newFile("state.json").writeNow(Sample("a", 1))

        assertEquals(Sample("a", 1), newFile("state.json").read())
    }

    @Test
    fun `missing file reads as null without throwing`() {
        assertNull(newFile("absent.json").read())
    }

    @Test
    fun `a corrupt file is quarantined and reads as null, never boot-fails`() {
        val path = dir.resolve("state.json")
        Files.writeString(path, "this is not json {{{")

        val file = newFile("state.json")
        assertNull(file.read())
        assertFalse(Files.exists(path), "the corrupt bytes move out of the way")

        val quarantined = Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().startsWith("state.json.corrupt-") }.findFirst()
        }
        assertTrue(quarantined.isPresent, "the corrupt file stays around for inspection")
        assertEquals("this is not json {{{", Files.readString(quarantined.get()))

        // and the store recovers: a fresh write lands cleanly afterwards
        file.writeNow(Sample("clean", 2))
        assertEquals(Sample("clean", 2), newFile("state.json").read())
    }

    @Test
    fun `an empty file counts as corrupt too`() {
        Files.writeString(dir.resolve("state.json"), "")

        assertNull(newFile("state.json").read())
        assertFalse(Files.exists(dir.resolve("state.json")))
    }

    @Test
    fun `a blocked write retries until the obstruction clears`() {
        val path = dir.resolve("state.json")
        // a directory sitting on the target path makes every move fail on
        // every platform until it is removed
        Files.createDirectory(path)

        val pool = Executors.newSingleThreadExecutor()
        val file = newFile("state.json", attempts = 6, delayMs = 40)
        pool.submit { file.write(Sample("persisted", 7)) }

        Thread.sleep(100)
        Files.delete(path)
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "the retried write finished")

        assertEquals("""{"name":"persisted","n":7}""", Files.readString(path).trim())
    }

    @Test
    fun `a write that never succeeds is logged and dropped, not thrown`() {
        val path = dir.resolve("state.json")
        Files.createDirectory(path) // permanent obstruction

        // must not throw even with every attempt failing
        newFile("state.json", attempts = 2, delayMs = 1).write(Sample("lost", 1))
        assertFalse(Files.isRegularFile(path), "the write never replaced the obstruction")
    }

    @Test
    fun `a newer writeNow is never rolled back by a queued async write`() {
        val path = dir.resolve("state.json")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val file = AtomicJsonFile(path, Sample.serializer(), pool, json)
            file.write(Sample("older", 1))
            file.writeNow(Sample("final", 2))
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))

            assertEquals(Sample("final", 2), newFile("state.json").read())
        } finally {
            pool.shutdownNow()
        }
    }
}
