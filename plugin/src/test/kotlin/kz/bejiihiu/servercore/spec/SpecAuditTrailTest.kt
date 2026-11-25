package kz.bejiihiu.servercore.spec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SpecAuditTrailTest {

    @TempDir
    lateinit var dir: Path

    private val zone = ZoneId.of("Europe/Moscow")

    private fun entry(
        ts: Long,
        event: String = SpecAuditEvents.START,
        sessionId: String? = "spec-1",
    ) = SpecAuditEntry(
        ts = ts,
        time = SpecAuditFiles.timeFor(ts, zone),
        admin = "069a79f4-44e9-4726-a5be-fca90e38aaf5",
        adminName = "Admin",
        target = "1b4b2f2e-1c2d-3e4f-5a6b-7c8d9e0f1a2b",
        targetName = "Target",
        event = event,
        sessionId = sessionId,
        reason = if (event == SpecAuditEvents.FORCED_END) "FORCED" else null,
        durationSec = if (event == SpecAuditEvents.START) null else 42,
    )

    private fun newTrail(
        retentionDays: Long = 30,
        maxEntriesPerSession: Int = 64,
        queueCapacity: Int = 1024,
        maxTrackedSessions: Int = 4096,
        clock: () -> Long = System::currentTimeMillis,
    ): SpecAuditTrail = SpecAuditTrail(
        directory = dir,
        io = Runnable::run, // synchronous: drain happens inside record()
        clock = clock,
        zone = zone,
        retentionDays = retentionDays,
        maxEntriesPerSession = maxEntriesPerSession,
        queueCapacity = queueCapacity,
        maxTrackedSessions = maxTrackedSessions,
    )

    private fun linesOf(file: Path): List<String> =
        Files.readAllLines(file).filter { it.isNotBlank() }

    @Test
    fun `entries land as jsonl with ts and moscow offset`() {
        // 2026-11-24T16:30:00Z = 19:30 moscow
        val ts = 1_795_537_800_000
        val trail = newTrail()
        trail.record(entry(ts))
        trail.flushNow()

        val file = dir.resolve(SpecAuditFiles.fileNameFor(ts, zone))
        assertTrue(Files.exists(file), "expected ${file.fileName} to exist")
        val parsed = Json.parseToJsonElement(linesOf(file).single()).jsonObject
        assertEquals(ts, parsed["ts"]!!.jsonPrimitive.longOrNull)
        assertTrue(parsed["time"]!!.jsonPrimitive.content.endsWith("+03:00"))
        assertEquals("SPEC_START", parsed["event"]!!.jsonPrimitive.content)
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", parsed["admin"]!!.jsonPrimitive.content)
        assertEquals("Target", parsed["targetName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `entries rotate into the file of their moscow day`() {
        // 23:30 moscow on 2026-11-24, then 00:30 moscow on 2026-11-25
        val day1 = 1_795_552_200_000
        val day2 = day1 + 3_600_000
        val trail = newTrail()
        trail.record(entry(day1, sessionId = "spec-1"))
        trail.record(entry(day2, sessionId = "spec-2"))
        trail.flushNow()

        assertTrue(Files.exists(dir.resolve(SpecAuditFiles.fileNameFor(day1, zone))))
        assertTrue(Files.exists(dir.resolve(SpecAuditFiles.fileNameFor(day2, zone))))
        assertEquals(1, linesOf(dir.resolve(SpecAuditFiles.fileNameFor(day1, zone))).size)
        assertEquals(1, linesOf(dir.resolve(SpecAuditFiles.fileNameFor(day2, zone))).size)
    }

    @Test
    fun `a single append batch never tears a line`() {
        val trail = newTrail()
        repeat(50) { trail.record(entry(1_795_537_800_000, sessionId = "spec-$it")) }
        trail.flushNow()
        val file = dir.resolve(SpecAuditFiles.fileNameFor(1_795_537_800_000, zone))
        val lines = linesOf(file)
        assertEquals(50, lines.size)
        for (line in lines) {
            // every line parses as complete json, no partial writes
            Json.parseToJsonElement(line).jsonObject
        }
    }

    @Test
    fun `a replayed start writes one row, not a twin`() {
        val trail = newTrail()
        val ts = 1_795_537_800_000
        // reconnect reconcile re-announces a live session, and a retried ws
        // frame re-runs the reporting bridge: same session, same event
        repeat(3) { trail.record(entry(ts, sessionId = "spec-1")) }
        trail.flushNow()

        val file = dir.resolve(SpecAuditFiles.fileNameFor(ts, zone))
        assertEquals(1, linesOf(file).size)
        assertEquals(2, trail.droppedForDuplicate())
    }

    @Test
    fun `a replayed end writes one row even after the session is terminal`() {
        val trail = newTrail()
        val ts = 1_795_537_800_000
        trail.record(entry(ts, sessionId = "spec-1"))
        trail.record(entry(ts, event = SpecAuditEvents.END, sessionId = "spec-1"))
        trail.record(entry(ts, event = SpecAuditEvents.END, sessionId = "spec-1"))
        trail.flushNow()

        val file = dir.resolve(SpecAuditFiles.fileNameFor(ts, zone))
        assertEquals(2, linesOf(file).size)
        assertEquals(1, trail.droppedForDuplicate())
    }

    @Test
    fun `the same event for another session is a different row`() {
        val trail = newTrail()
        val ts = 1_795_537_800_000
        trail.record(entry(ts, sessionId = "spec-1"))
        trail.record(entry(ts, sessionId = "spec-2"))
        trail.flushNow()

        val file = dir.resolve(SpecAuditFiles.fileNameFor(ts, zone))
        assertEquals(2, linesOf(file).size)
        assertEquals(0, trail.droppedForDuplicate())
    }

    @Test
    fun `the dedupe window stays bounded by the coldest sessions`() {
        val trail = newTrail(maxTrackedSessions = 2)
        val ts = 1_795_537_800_000
        for (sessionId in listOf("spec-1", "spec-2", "spec-3")) {
            trail.record(entry(ts, sessionId = sessionId))
        }
        // spec-1 was evicted from the window, its memory is gone: a late replay
        // is accepted again rather than the map growing forever
        trail.record(entry(ts, sessionId = "spec-1"))
        trail.record(entry(ts, sessionId = "spec-3"))
        trail.flushNow()

        val file = dir.resolve(SpecAuditFiles.fileNameFor(ts, zone))
        assertEquals(4, linesOf(file).size)
        assertEquals(1, trail.droppedForDuplicate())
    }

    @Test
    fun `retention prunes files older than the window`() {
        val today = LocalDate.parse("2026-11-24")
        var now = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val oldDay = today.minusDays(31).atStartOfDay(zone).toInstant().toEpochMilli()
        val freshDay = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // seed two expired files and one fresh one by hand
        Files.writeString(dir.resolve(SpecAuditFiles.fileNameFor(oldDay, zone)), "{}\n")
        Files.writeString(dir.resolve(SpecAuditFiles.fileNameFor(freshDay, zone)), "{}\n")
        Files.writeString(dir.resolve("not-an-audit-file.jsonl"), "{}\n")

        val trail = newTrail(retentionDays = 30, clock = { now })
        trail.record(entry(now))
        trail.flushNow()

        assertFalse(Files.exists(dir.resolve(SpecAuditFiles.fileNameFor(oldDay, zone))), "31d-old file must go")
        assertTrue(Files.exists(dir.resolve(SpecAuditFiles.fileNameFor(freshDay, zone))))
        // unrelated files are never touched
        assertTrue(Files.exists(dir.resolve("not-an-audit-file.jsonl")))
    }

    @Test
    fun `per-session cap drops the overflow, not the session`() {
        val trail = newTrail(maxEntriesPerSession = 3)
        val ts = 1_795_537_800_000
        // distinct events on purpose: the same event twice is a replay and dies
        // before the cap even sees it
        repeat(3) { trail.record(entry(ts, event = "SPEC_CUSTOM_$it", sessionId = "spec-1")) }
        trail.record(entry(ts, event = SpecAuditEvents.END, sessionId = "spec-1"))
        // another session is unaffected by the first one's cap
        trail.record(entry(ts, sessionId = "spec-2"))
        trail.flushNow()

        val file = dir.resolve(SpecAuditFiles.fileNameFor(ts, zone))
        assertEquals(4, linesOf(file).size)
        assertEquals(1, trail.droppedForSessionCap())
    }

    @Test
    fun `the per-session counter is released when the session ends`() {
        val trail = newTrail(maxEntriesPerSession = 3)
        val ts = 1_795_537_800_000
        repeat(3) { trail.record(entry(ts, event = "SPEC_CUSTOM_A$it", sessionId = "spec-1")) }
        // the end row is terminal: it gets dropped by the cap, and the counter
        // goes with it instead of lingering for the process lifetime
        trail.record(entry(ts, event = SpecAuditEvents.END, sessionId = "spec-1"))
        repeat(3) { trail.record(entry(ts, event = "SPEC_CUSTOM_B$it", sessionId = "spec-1")) }
        trail.flushNow()

        val file = dir.resolve(SpecAuditFiles.fileNameFor(ts, zone))
        assertEquals(6, linesOf(file).size)
        assertEquals(1, trail.droppedForSessionCap())
    }

    @Test
    fun `a full buffer drops entries instead of blocking`() {
        // an executor that only queues: nothing drains until we say so, so the
        // in-memory buffer actually fills up
        val queued = mutableListOf<Runnable>()
        val trail = SpecAuditTrail(
            directory = dir,
            io = { task -> queued.add(task) },
            clock = System::currentTimeMillis,
            zone = zone,
            queueCapacity = 2,
        )
        val ts = 1_795_537_800_000
        trail.record(entry(ts, sessionId = "spec-1"))
        trail.record(entry(ts, sessionId = "spec-2"))
        trail.record(entry(ts, sessionId = "spec-3"))
        // nothing threw, the third entry was dropped and counted
        assertEquals(1, trail.droppedForFullBuffer())
        trail.flushNow()
        assertEquals(2, linesOf(dir.resolve(SpecAuditFiles.fileNameFor(ts, zone))).size)
    }

    @Test
    fun `file naming helpers round-trip and reject foreign names`() {
        val ts = 1_795_537_800_000
        val name = SpecAuditFiles.fileNameFor(ts, zone)
        assertEquals("spec-audit-2026-11-24.jsonl", name)
        assertEquals(LocalDate.parse("2026-11-24"), SpecAuditFiles.parseFileDate(name))
        assertEquals(null, SpecAuditFiles.parseFileDate("guest-snapshots.json"))
        assertEquals(null, SpecAuditFiles.parseFileDate("spec-audit-garbage.jsonl"))
        assertTrue(SpecAuditFiles.isExpired("spec-audit-2026-10-01.jsonl", LocalDate.parse("2026-11-24"), 30))
        assertFalse(SpecAuditFiles.isExpired("spec-audit-2026-11-01.jsonl", LocalDate.parse("2026-11-24"), 30))
    }
}
