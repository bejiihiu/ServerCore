package kz.bejiihiu.servercore.spec

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** audit event kinds, one line each in the audit file */
enum class SpecAuditEvent { SPEC_STARTED, SPEC_ENDED, SPEC_DENIED }

/**
 * one audit line. admin/target uuids stay raw so the log never depends on a
 * nickname staying stable; names ride along for humans.
 */
@Serializable
data class SpecAuditRecord(
    val ts: String,
    val event: String,
    val admin: String,
    val adminName: String,
    val target: String? = null,
    val targetName: String? = null,
    val detail: String? = null,
)

/**
 * local audit log for observation sessions: append-only jsonl with size-based
 * rotation. every start, stop and denial lands here whether or not the backend
 * websocket is up - the ops seam only carries server status frames, session
 * auditing is deliberately local.
 *
 * all writes go through the io executor (virtual threads for us) and serialize
 * on one lock; rotation shifts spec-audit-1..N and truncates the base file.
 */
class SpecAuditWriter(
    private val baseFile: Path,
    private val maxFileBytes: Long,
    private val maxKept: Int,
    private val io: Executor,
    private val zone: ZoneId = ZoneId.of("Europe/Moscow"),
) {

    private val lock = ReentrantLock()
    private val json = Json { encodeDefaults = false }

    private val rotatedPrefix = baseFile.fileName.toString().replace(Regex("\\.jsonl$"), "")
    private val suffix = baseFile.fileName.toString().substringAfterLast('.', "")

    fun record(event: SpecAuditEvent, admin: String, adminName: String, target: String? = null, targetName: String? = null, detail: String? = null) {
        val line = json.encodeToString(
            SpecAuditRecord.serializer(),
            SpecAuditRecord(
                ts = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atZone(zone)),
                event = event.name,
                admin = admin,
                adminName = adminName,
                target = target,
                targetName = targetName,
                detail = detail,
            ),
        )
        io.execute {
            runCatching { appendLine(line) }.onFailure { error ->
                System.err.println("servercore: audit write failed, line dropped ($error)")
            }
        }
    }

    /** blocking drain for shutdown: waits until every queued append hit the disk */
    fun flush() {
        val barrier = java.util.concurrent.CountDownLatch(1)
        io.execute { barrier.countDown() }
        barrier.await()
    }

    private fun appendLine(line: String) {
        lock.lock()
        try {
            Files.createDirectories(baseFile.parent)
            if (needsRotation(line.length)) rotate()
            Files.writeString(
                baseFile,
                line + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
            )
        } finally {
            lock.unlock()
        }
    }

    private fun needsRotation(incomingBytes: Int): Boolean {
        if (!Files.exists(baseFile)) return false
        val current = Files.size(baseFile)
        return current + incomingBytes + 1 > maxFileBytes
    }

    /** base -> base-1 -> base-2 ... oldest rotated file falls off the cliff */
    private fun rotate() {
        val extension = if (suffix.isEmpty()) "" else ".$suffix"
        // drop the oldest first so the shift never collides
        val oldest = baseFile.resolveSibling("$rotatedPrefix-$maxKept$extension")
        Files.deleteIfExists(oldest)
        for (i in maxKept - 1 downTo 1) {
            val from = baseFile.resolveSibling("$rotatedPrefix-$i$extension")
            if (Files.exists(from)) {
                Files.move(from, baseFile.resolveSibling("$rotatedPrefix-${i + 1}$extension"), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        Files.move(baseFile, baseFile.resolveSibling("$rotatedPrefix-1$extension"), StandardCopyOption.REPLACE_EXISTING)
    }
}
