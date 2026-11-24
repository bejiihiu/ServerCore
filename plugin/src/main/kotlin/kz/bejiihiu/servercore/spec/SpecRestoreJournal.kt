package kz.bejiihiu.servercore.spec

import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kz.bejiihiu.servercore.util.AtomicJsonFile

/**
 * durable record of an admin state that could not be restored live - the admin
 * quit mid-session or the server died under an active observation. written the
 * moment the session goes ACTIVE, removed as soon as the state is back. on the
 * next join of that admin the journal replays the restore.
 *
 * sessions themselves never survive a restart; the journal only guarantees the
 * admin does not stay stranded in spectator mode.
 */
@Serializable
data class PendingRestore(
    @SerialName("admin") val adminId: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val gamemode: String,
    val flying: Boolean,
    val allowFlight: Boolean,
    val walkSpeed: Float,
    val flySpeed: Float,
)

class SpecRestoreJournal(path: java.nio.file.Path, io: Executor) {

    private val file = AtomicJsonFile(
        path,
        kotlinx.serialization.builtins.ListSerializer(PendingRestore.serializer()),
        io,
        kotlinx.serialization.json.Json,
    )

    private val entries = java.util.concurrent.ConcurrentHashMap<UUID, PendingRestore>()

    /** blocking, startup only */
    fun load() {
        file.read().orEmpty().forEach { record ->
            runCatching { UUID.fromString(record.adminId) }.getOrNull()?.let { uuid -> entries[uuid] = record }
        }
    }

    fun pending(adminId: UUID): PendingRestore? = entries[adminId]

    fun put(record: PendingRestore) {
        entries[UUID.fromString(record.adminId)] = record
        file.write(entries.values.toList())
    }

    fun remove(adminId: UUID) {
        if (entries.remove(adminId) != null) {
            file.write(entries.values.toList())
        }
    }

    /** blocking write for shutdown so the queue cannot lose the last entry */
    fun shutdown() {
        file.writeNow(entries.values.toList())
    }
}
