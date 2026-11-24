package kz.bejiihiu.servercore.guest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kz.bejiihiu.servercore.util.AtomicJsonFile
import kotlinx.serialization.builtins.MapSerializer

/** one durable guest snapshot: captured once, never overwritten by later sessions */
@Serializable
data class StoredSnapshot(
    val sessionId: String,
    val createdAt: Long,
    val snapshot: PlayerSnapshot,
)

@Serializable
private data class SnapshotFile(
    val version: Int = 1,
    val snapshots: Map<String, StoredSnapshot> = emptyMap(),
)

/**
 * local fallback storage for guest snapshots, survives folia restarts. the
 * backend keeps its own copy in mongodb via guest_started, this file is the
 * safety net for when the backend was down at the wrong moment.
 */
class SnapshotStore(path: Path, io: Executor) {

    private val json = Json { ignoreUnknownKeys = true }
    private val file = AtomicJsonFile(path, SnapshotFile.serializer(), io, json)
    private val entries = ConcurrentHashMap<UUID, StoredSnapshot>()

    /** blocking, startup only */
    fun load() {
        file.read()?.snapshots?.forEach { (key, value) ->
            runCatching { UUID.fromString(key) }.getOrNull()?.let { uuid -> entries[uuid] = value }
        }
    }

    /** first snapshot for a player, called exactly once per original session */
    fun save(uuid: UUID, stored: StoredSnapshot) {
        entries[uuid] = stored
        flush()
    }

    /** rebinds the durable snapshot to a fresh session id without touching the captured values */
    fun updateSessionId(uuid: UUID, sessionId: String) {
        entries.computeIfPresent(uuid) { _, existing -> existing.copy(sessionId = sessionId) }
        flush()
    }

    /** snapshot reached its final state (player authenticated), drop it */
    fun complete(uuid: UUID) {
        entries.remove(uuid)
        flush()
    }

    fun pending(uuid: UUID): StoredSnapshot? = entries[uuid]

    fun pendingAll(): Map<UUID, StoredSnapshot> = entries.toMap()

    /** restore-on-restart off: snapshots left by a previous run are dropped, not restored */
    fun clearPending() {
        if (entries.isEmpty()) return
        entries.clear()
        flush()
    }

    /** final synchronous flush for onDisable */
    fun shutdown() {
        file.writeNow(SnapshotFile(snapshots = entries.mapKeys { (uuid, _) -> uuid.toString() }))
    }

    private fun flush() {
        file.write(SnapshotFile(snapshots = entries.mapKeys { (uuid, _) -> uuid.toString() }))
    }
}
