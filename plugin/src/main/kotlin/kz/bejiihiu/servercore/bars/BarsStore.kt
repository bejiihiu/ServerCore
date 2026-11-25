package kz.bejiihiu.servercore.bars

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kz.bejiihiu.servercore.util.AtomicJsonFile

/** the on-disk shape of bars.json; entries that no longer parse are dropped on load */
@Serializable
internal data class BarsFile(val players: Map<String, Map<String, String>> = emptyMap())

/**
 * per-player bar choices, durable across relogs. this is the one thing purpur
 * deliberately does not do (their toggle dies with the session), so the file
 * is the feature, not an implementation detail.
 *
 * reads happen once at boot; writes are async through the plugin io pool and
 * flushed synchronously on shutdown so a quit racing a save still lands.
 */
class BarsStore(
    path: Path,
    io: Executor,
    json: Json = Json { ignoreUnknownKeys = true },
) {

    private val file = AtomicJsonFile(path, BarsFile.serializer(), io, json)

    fun load(): Map<UUID, Map<BarSurface, BarId>> {
        val content = file.read() ?: return emptyMap()
        val loaded = HashMap<UUID, Map<BarSurface, BarId>>()
        for ((rawUuid, bySurface) in content.players) {
            val uuid = runCatching { UUID.fromString(rawUuid) }.getOrNull() ?: continue
            val bars = bySurface.entries.mapNotNull { (rawSurface, rawBar) ->
                val surface = BarSurface.parse(rawSurface) ?: return@mapNotNull null
                val bar = BarId.entries.firstOrNull { it.key == rawBar } ?: return@mapNotNull null
                surface to bar
            }.toMap()
            if (bars.isNotEmpty()) loaded[uuid] = bars
        }
        return loaded
    }

    fun write(snapshot: Map<String, Map<String, String>>) {
        file.write(BarsFile(snapshot))
    }

    fun writeNow(snapshot: Map<String, Map<String, String>>) {
        file.writeNow(BarsFile(snapshot))
    }
}
