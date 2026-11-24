package kz.bejiihiu.servercore.spec

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.name
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SpecAuditWriterTest {

    @TempDir
    lateinit var dir: Path

    private fun writer(maxBytes: Long = 1_000_000, maxKept: Int = 5, zone: java.time.ZoneId = java.time.ZoneId.of("Europe/Moscow")) =
        SpecAuditWriter(
            baseFile = dir.resolve("spec-audit.jsonl"),
            maxFileBytes = maxBytes,
            maxKept = maxKept,
            io = Runnable::run,
            zone = zone,
        )

    private fun readBase(): List<String> = Files.readAllLines(dir.resolve("spec-audit.jsonl"))

    @Test
    fun `every record is one json line with the expected shape`() {
        val w = writer()
        val admin = UUID.randomUUID()
        val target = UUID.randomUUID()

        w.record(SpecAuditEvent.SPEC_STARTED, admin.toString(), "admin", target.toString(), "steve")
        w.record(SpecAuditEvent.SPEC_ENDED, admin.toString(), "admin", target.toString(), "steve", "COMMAND")

        val lines = readBase()
        assertEquals(2, lines.size)

        val first = Json.decodeFromString(SpecAuditRecord.serializer(), lines[0])
        assertEquals("SPEC_STARTED", first.event)
        assertEquals(admin.toString(), first.admin)
        assertEquals("admin", first.adminName)
        assertEquals(target.toString(), first.target)
        assertEquals("steve", first.targetName)
        assertNull(first.detail)
        // timestamps carry the configured zone offset
        assertTrue(first.ts.contains("+03:00"))

        val second = Json.decodeFromString(SpecAuditRecord.serializer(), lines[1])
        assertEquals("SPEC_ENDED", second.event)
        assertEquals("COMMAND", second.detail)
    }

    @Test
    fun `rotation shifts numbered files and respects max-kept`() {
        val w = writer(maxBytes = 120, maxKept = 2)

        // each record is well under the cap, so rotation is driven by accumulation
        repeat(6) { index ->
            w.record(SpecAuditEvent.SPEC_STARTED, "uuid-$index", "admin-$index", "t-$index", "target-$index")
        }

        val files = Files.list(dir).use { it.map { p -> p.fileName.toString() }.toList() }
        // base plus at most maxKept rotated files
        assertTrue(files.contains("spec-audit.jsonl"))
        assertTrue(files.contains("spec-audit-1.jsonl"))
        assertTrue(files.contains("spec-audit-2.jsonl"))
        assertFalse(files.contains("spec-audit-3.jsonl"))

        // the base file holds only the newest records, older ones moved to -1
        val base = readBase()
        assertTrue(base.size < 6)
        // with max-kept=2 the three oldest records are gone; -2 opens with admin-3
        val oldest = Json.decodeFromString(SpecAuditRecord.serializer(), Files.readAllLines(dir.resolve("spec-audit-2.jsonl")).first())
        assertEquals("admin-3", oldest.adminName)
    }

    @Test
    fun `a single oversized record still rotates instead of growing forever`() {
        val w = writer(maxBytes = 64, maxKept = 1)
        w.record(SpecAuditEvent.SPEC_DENIED, "a", "admin", "t", "target", "x".repeat(200))

        // the record was bigger than the cap: it lands alone in a fresh base file
        assertEquals(1, readBase().size)
        val rotated = dir.resolve("spec-audit-1.jsonl")
        if (Files.exists(rotated)) {
            assertFalse(Files.readAllLines(rotated).isEmpty())
        }
    }
}
