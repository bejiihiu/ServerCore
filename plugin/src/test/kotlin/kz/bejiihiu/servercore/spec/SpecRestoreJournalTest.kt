package kz.bejiihiu.servercore.spec

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SpecRestoreJournalTest {

    @TempDir
    lateinit var dir: Path

    private fun newJournal() = SpecRestoreJournal(dir.resolve("journal.json"), Runnable::run)

    private fun record(uuid: UUID) = PendingRestore(
        adminId = uuid.toString(),
        world = "world",
        x = 1.5,
        y = 64.0,
        z = -3.25,
        yaw = 90f,
        pitch = -10f,
        gamemode = "SURVIVAL",
        flying = false,
        allowFlight = false,
        walkSpeed = 0.2f,
        flySpeed = 0.1f,
    )

    @Test
    fun `entries survive a reload`() {
        val admin = UUID.randomUUID()
        val journal = newJournal()
        journal.put(record(admin))
        journal.shutdown()

        val reloaded = newJournal()
        reloaded.load()
        val restored = reloaded.pending(admin)
        assertTrue(restored != null)
        assertEquals("world", restored!!.world)
        assertEquals("SURVIVAL", restored.gamemode)
        assertEquals(90f, restored.yaw)
    }

    @Test
    fun `remove clears the entry and the file`() {
        val admin = UUID.randomUUID()
        val journal = newJournal()
        journal.put(record(admin))
        journal.remove(admin)

        assertNull(journal.pending(admin))
        journal.shutdown()

        val reloaded = newJournal()
        reloaded.load()
        assertNull(reloaded.pending(admin))
        assertTrue(Files.exists(dir.resolve("journal.json")))
    }

    @Test
    fun `remove after the io pool shut down still cleans the file`() {
        // regression: the async write of a remove racing the shutdown flush
        // used to be dropped (seq guard) or rejected (io pool gone), leaving a
        // stale entry in the file that replayed on the next join and yanked
        // the admin back to their pre-session position
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val journal = SpecRestoreJournal(dir.resolve("journal-async.json"), executor)
        val admin = UUID.randomUUID()
        journal.put(record(admin))

        // like onDisable: pool no longer accepts new work, queued put still runs
        executor.shutdown()
        journal.remove(admin)

        assertNull(journal.pending(admin))
        val reloaded = SpecRestoreJournal(dir.resolve("journal-async.json"), Runnable::run)
        reloaded.load()
        assertNull(reloaded.pending(admin))
    }
}
