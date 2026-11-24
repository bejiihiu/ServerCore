package kz.bejiihiu.servercore.guest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.CompletableFuture
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kz.bejiihiu.servercore.util.AtomicJsonFile

@Serializable
data class CachedAccount(
    val status: String,
    val updatedAt: Long,
)

@Serializable
private data class AccountCacheFile(
    val version: Int = 1,
    val accounts: Map<String, CachedAccount> = emptyMap(),
)

/**
 * local memory of durable account facts the backend told us about (active,
 * banned). lets the local resolver make sane calls while the backend is down
 * instead of treating known players as strangers.
 */
class LocalAccountCache(path: Path, io: Executor) {

    private val json = Json { ignoreUnknownKeys = true }
    private val file = AtomicJsonFile(path, AccountCacheFile.serializer(), io, json)
    private val entries = ConcurrentHashMap<UUID, CachedAccount>()

    /** blocking, startup only */
    fun load() {
        file.read()?.accounts?.forEach { (key, value) ->
            runCatching { UUID.fromString(key) }.getOrNull()?.let { uuid -> entries[uuid] = value }
        }
    }

    fun get(uuid: UUID): CachedAccount? = entries[uuid]

    fun put(uuid: UUID, status: String) {
        entries[uuid] = CachedAccount(status.uppercase(), System.currentTimeMillis())
        flush()
    }

    fun remove(uuid: UUID) {
        entries.remove(uuid)
        flush()
    }

    /** final synchronous flush for onDisable */
    fun shutdown() {
        file.writeNow(AccountCacheFile(accounts = entries.mapKeys { (uuid, _) -> uuid.toString() }))
    }

    private fun flush() {
        file.write(AccountCacheFile(accounts = entries.mapKeys { (uuid, _) -> uuid.toString() }))
    }
}

/**
 * config + local cache resolver, the fallback path when the backend is not
 * reachable. unknown players fail closed to guest mode.
 */
class LocalAccountResolver(
    private val config: GuestConfig,
    private val cache: LocalAccountCache,
) : AccountStateResolver {

    // instant, everything it needs is already in memory
    override fun resolve(uuid: UUID, username: String): CompletableFuture<AccountStatus> {
        val status = when (config.localAccounts[uuid]) {
            "authorized" -> AccountStatus.Authorized
            "banned" -> AccountStatus.Denied(DenyCode.BANNED)
            else -> AccountStateMapper.fromCached(cache.get(uuid)?.status)
        }
        return CompletableFuture.completedFuture(status)
    }
}
