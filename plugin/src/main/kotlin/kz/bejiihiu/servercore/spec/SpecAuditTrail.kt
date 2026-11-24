package kz.bejiihiu.servercore.spec

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** audit event kinds, one session produces at most one of each */
object SpecAuditEvents {
    const val START = "SPEC_START"
    const val END = "SPEC_END"
    const val FORCED_END = "SPEC_FORCED_END"
}

/**
 * one jsonl audit row. only uuids and usernames travel here — the audit trail
 * must not become a second store of personal data. `ts` is epoch millis,
 * `time` is the same instant rendered in the audit timezone with its offset
 * (europe/moscow per project standard).
 */
@Serializable
data class SpecAuditEntry(
    val ts: Long,
    val time: String,
    val admin: String,
    val adminName: String? = null,
    val target: String,
    val targetName: String? = null,
    val event: String,
    val sessionId: String? = null,
    /** why the session ended, present on SPEC_FORCED_END */
    val reason: String? = null,
    /** wall duration of the session in seconds, present on ends */
    val durationSec: Long? = null,
)

/**
 * naming, parsing and pruning rules for the audit files. kept pure so the
 * tests can exercise rotation and retention without any io.
 */
object SpecAuditFiles {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    /** spec-audit-YYYY-MM-DD.jsonl, dated in the audit zone */
    fun fileNameFor(epochMs: Long, zone: ZoneId): String = "spec-audit-${dateFor(epochMs, zone)}.jsonl"

    fun dateFor(epochMs: Long, zone: ZoneId): String =
        dateFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** human-readable wall clock with the zone offset, e.g. ...+03:00 */
    fun timeFor(epochMs: Long, zone: ZoneId): String =
        timeFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** null when the name is not one of our rotated audit files */
    fun parseFileDate(fileName: String): LocalDate? {
        if (!fileName.startsWith("spec-audit-") || !fileName.endsWith(".jsonl")) return null
        val date = fileName.removePrefix("spec-audit-").removeSuffix(".jsonl")
        return runCatching { LocalDate.parse(date, dateFormat) }.getOrNull()
    }

    /** strictly older than the retention window means deletable */
    fun isExpired(fileName: String, today: LocalDate, retentionDays: Long): Boolean {
        val date = parseFileDate(fileName) ?: return false
        return date.isBefore(today.minusDays(retentionDays))
    }
}

/**
 * append-only audit journal for spec sessions, written as jsonl with daily
 * rotation: entries for a given audit-zone day all land in the same
 * `spec-audit-YYYY-MM-DD.jsonl` file.
 *
 * concurrency model: callers (region threads, ws callbacks) only append to a
 * bounded in-memory buffer and never touch the disk — record() cannot block.
 * a single drain task on the io executor (virtual threads for us) swaps the
 * buffer and appends each day's batch with one create/append write, so a line
 * is either fully on disk or not at all. overflow drops the newest entry and
 * counts it; the same goes for sessions that exceed their entry cap.
 */
class SpecAuditTrail(
    private val directory: Path,
    private val io: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.of("Europe/Moscow"),
    private val retentionDays: Long = 30,
    private val maxEntriesPerSession: Int = 64,
    private val queueCapacity: Int = 1024,
) {

    private val json = Json { encodeDefaults = false }
    private val buffer = ArrayDeque<SpecAuditEntry>()
    private val drainLock = Any()
    private val perSessionCounts = ConcurrentHashMap<String, Int>()
    private val droppedByFullBuffer = AtomicLong()
    private val droppedBySessionCap = AtomicLong()

    @Volatile
    private var drainScheduled = false

    @Volatile
    private var closed = false

    private var lastPrunedDay: String? = null

    /**
     * enqueue one audit row. never blocks, never throws: a full buffer or an
     * over-cap session drops the entry and bumps a counter instead.
     */
    fun record(entry: SpecAuditEntry) {
        if (closed) return
        if (entry.sessionId != null) {
            val count = perSessionCounts.merge(entry.sessionId, 1, Int::plus)!!
            if (count > maxEntriesPerSession) {
                droppedBySessionCap.incrementAndGet()
                return
            }
        }
        val offered = synchronized(drainLock) {
            if (buffer.size >= queueCapacity) {
                false
            } else {
                buffer.addLast(entry)
                true
            }
        }
        if (!offered) {
            droppedByFullBuffer.incrementAndGet()
            return
        }
        scheduleDrain()
    }

    /** total entries dropped because the buffer was full */
    fun droppedForFullBuffer(): Long = droppedByFullBuffer.get()

    /** total entries dropped because their session went over the cap */
    fun droppedForSessionCap(): Long = droppedBySessionCap.get()

    /** entries waiting for the next drain, for tests and shutdown checks */
    fun pendingCount(): Int = synchronized(drainLock) { buffer.size }

    /** blocking drain, for shutdown and tests */
    fun flushNow() {
        drainBlocking()
    }

    /** stops accepting entries and flushes whatever is left */
    fun close() {
        closed = true
        flushNow()
    }

    private fun scheduleDrain() {
        synchronized(drainLock) {
            if (drainScheduled || closed) return
            drainScheduled = true
        }
        io.execute {
            drainScheduled = false
            if (closed) {
                // close() does the final flush itself
                return@execute
            }
            runCatching { drainBlocking() }.onFailure { error ->
                System.err.println("servercore: spec audit drain failed: $error")
            }
        }
    }

    /** swaps the buffer and writes each day's batch to its rotated file */
    private fun drainBlocking() {
        val batch = synchronized(drainLock) {
            val snapshot = ArrayDeque(buffer)
            buffer.clear()
            snapshot
        }
        if (batch.isEmpty() && lastPrunedDay != null) return

        Files.createDirectories(directory)
        val byDay = LinkedHashMap<String, StringBuilder>()
        for (entry in batch) {
            byDay.getOrPut(SpecAuditFiles.dateFor(entry.ts, zone)) { StringBuilder() }
                .append(json.encodeToString(SpecAuditEntry.serializer(), entry))
                .append('\n')
        }
        for ((day, text) in byDay) {
            val file = directory.resolve("spec-audit-$day.jsonl")
            Files.writeString(
                file,
                text.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
            )
        }

        // retention: once per audit-zone day, drop files past their lifetime
        val today = SpecAuditFiles.dateFor(clock(), zone)
        if (today != lastPrunedDay) {
            lastPrunedDay = today
            pruneExpired(today)
        }
    }

    private fun pruneExpired(today: String) {
        val cutoff = LocalDate.parse(today).minusDays(retentionDays)
        runCatching {
            Files.list(directory).use { files ->
                for (file in files) {
                    val name = file.fileName.toString()
                    val date = SpecAuditFiles.parseFileDate(name) ?: continue
                    if (date.isBefore(cutoff)) {
                        runCatching { Files.deleteIfExists(file) }
                            .onFailure { System.err.println("servercore: cannot prune $name: $it") }
                    }
                }
            }
        }.onFailure { System.err.println("servercore: spec audit prune failed: $it") }
    }
}
