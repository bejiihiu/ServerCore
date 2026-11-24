package kz.bejiihiu.servercore.integration.flectonepulse

import java.net.InetAddress
import java.util.Optional
import java.util.UUID
import net.flectone.pulse.config.setting.ViolationSetting
import net.flectone.pulse.model.entity.FEntity
import net.flectone.pulse.model.entity.FPlayer
import net.flectone.pulse.model.entity.FPlayerImpl
import net.flectone.pulse.model.value.Moderation
import net.flectone.pulse.module.ModuleSimple
import net.flectone.pulse.service.ModerationService
import net.flectone.pulse.service.SocialService

/**
 * fake flectonepulse service surface for tests: the real interfaces from the
 * api artifact, backed by plain maps. mutation methods count their calls so
 * a test can prove servercore's read-only boundary actually holds.
 */
class FakeModerationService(
    /** uuid -> type -> active moderation, pre-trimmed to what getValid would return */
    private val validByPlayer: MutableMap<Pair<UUID, Moderation.Type>, MutableList<Moderation>> = mutableMapOf(),
) : ModerationService {

    var mutationCalls = 0
        private set

    var lookupCalls = 0
        private set

    fun addValid(uuid: UUID, type: Moderation.Type, moderation: Moderation) {
        validByPlayer.getOrPut(uuid to type) { mutableListOf() }.add(moderation)
    }

    private fun failMutation(): Nothing {
        mutationCalls++
        throw UnsupportedOperationException("servercore must never mutate flectonepulse moderation state")
    }

    override fun getValid(fPlayer: FPlayer, type: Moderation.Type): Optional<Moderation> {
        lookupCalls++
        val found = validByPlayer[fPlayer.uuid() to type]
            ?.firstOrNull { it.valid() && (it.isPermanent() || !it.isExpired()) }
        return Optional.ofNullable(found)
    }

    // everything below is out of scope for the read-only surface under test

    override fun invalidate() = Unit
    override fun invalidate(uuid: UUID) = Unit
    override fun invalidate(fPlayer: FPlayer, type: Moderation.Type, id: Int) = Unit
    override fun invalidate(fPlayer: FPlayer, type: Moderation.Type, id: Int, server: String?) = Unit
    override fun invalidate(uuid: UUID, type: Moderation.Type) = Unit

    override fun ban(fPlayer: FPlayer, time: Long, reason: String, silent: Int): Moderation = failMutation()
    override fun mute(fPlayer: FPlayer, time: Long, reason: String, silent: Int): Moderation = failMutation()
    override fun maintenance(fPlayer: FPlayer, time: Long, reason: String, silent: Int): Moderation = failMutation()
    override fun warn(fPlayer: FPlayer, time: Long, reason: String, silent: Int): Moderation = failMutation()
    override fun kick(fPlayer: FPlayer, reason: String, silent: Int): Moderation = failMutation()
    override fun whitelist(fPlayer: FPlayer, time: Long, reason: String, silent: Int): Moderation = failMutation()

    override fun add(fPlayer: FPlayer, time: Long, reason: String, silent: Int, type: Moderation.Type): Moderation =
        failMutation()

    override fun add(
        fPlayer: FPlayer,
        time: Long,
        maxTime: Long,
        reason: String,
        silent: Int,
        type: Moderation.Type,
        server: String?,
    ): Moderation = failMutation()

    override fun remove(fPlayer: FPlayer, moderator: FPlayer, type: Moderation.Type, id: Int, reason: String?): Moderation =
        failMutation()

    override fun remove(
        fPlayer: FPlayer,
        moderator: FPlayer,
        type: Moderation.Type,
        id: Int,
        reason: String?,
        server: String?,
    ): Moderation = failMutation()

    override fun remove(
        fPlayer: FPlayer,
        moderator: FPlayer,
        type: Moderation.Type,
        time: Long,
        id: Int,
        reason: String?,
    ): Moderation = failMutation()

    override fun remove(
        fPlayer: FPlayer,
        moderator: FPlayer,
        type: Moderation.Type,
        time: Long,
        id: Int,
        reason: String?,
        server: String?,
    ): Moderation = failMutation()

    override fun hasValid(fPlayer: FPlayer, type: Moderation.Type): Boolean {
        lookupCalls++
        return getValid(fPlayer, type).isPresent
    }

    override fun hasValid(fPlayer: FPlayer, type: Moderation.Type, id: Int): Boolean = false

    override fun getAll(fPlayer: FPlayer, type: Moderation.Type, server: String?, offset: Int, limit: Int): List<Moderation> =
        emptyList()

    override fun getValid(fPlayer: FPlayer, type: Moderation.Type, server: String, offset: Int, limit: Int): List<Moderation> =
        emptyList()

    override fun getValid(fPlayer: FPlayer, type: Moderation.Type, offset: Int, limit: Int): List<Moderation> =
        emptyList()

    override fun getValid(type: Moderation.Type, offset: Int, limit: Int): List<Moderation> = emptyList()
    override fun getValid(type: Moderation.Type, id: Int): Optional<Moderation> = Optional.empty()
    override fun getValidNames(type: Moderation.Type): List<String> = emptyList()
    override fun getTotalCount(fPlayer: FPlayer, type: Moderation.Type, server: String?): Int = 0
    override fun getTotalValidCount(fPlayer: FPlayer, type: Moderation.Type, server: String?): Int = 0
    override fun getTotalValidCount(type: Moderation.Type, server: String?): Int = 0

    override fun addViolation(uuid: UUID, module: ModuleSimple, setting: ViolationSetting) = failMutation()
    override fun addViolation(key: ModerationService.ViolationKey, time: Long?) = failMutation()
    override fun isViolationRestricted(uuid: UUID, module: ModuleSimple, setting: ViolationSetting): Boolean = false
    override fun getFirstViolationTimestamp(uuid: UUID, module: ModuleSimple): Long? = null

    override fun isAllowedTime(fPlayer: FPlayer, time: Long, allowed: Map<Int, Long>): Boolean = true
    override fun hasHigherGroupThan(first: FPlayer, second: FPlayer): Boolean = false
    override fun getServer(type: Moderation.Type): String = "*"
}

/** fake fplayer service: just a uuid lookup table, everything else unused */
class FakeFPlayerService(private val players: Map<UUID, FPlayer> = emptyMap()) :
    net.flectone.pulse.service.FPlayerService {

    override fun getFPlayer(uuid: UUID): FPlayer = players[uuid] ?: FPlayer.UNKNOWN

    override fun getFPlayer(id: Int): FPlayer = FPlayer.UNKNOWN
    override fun getFPlayer(name: String): FPlayer = FPlayer.UNKNOWN
    override fun getFPlayer(address: InetAddress): FPlayer = FPlayer.UNKNOWN
    override fun getFPlayer(entity: FEntity): FPlayer = FPlayer.UNKNOWN
    override fun getFPlayer(source: Any): FPlayer = FPlayer.UNKNOWN
    override fun getFPlayerFromDatabase(uuid: UUID): FPlayer = FPlayer.UNKNOWN
    override fun getConsole(): FPlayer = FPlayer.UNKNOWN
    override fun getRandomFPlayer(): FPlayer = FPlayer.UNKNOWN
    override fun getFPlayersByIp(ip: String): List<FPlayer> = emptyList()
    override fun findAllFPlayers(): List<FPlayer> = emptyList()
    override fun getOnlineFPlayers(): List<FPlayer> = emptyList()
    override fun getPlatformFPlayers(): List<FPlayer> = emptyList()
    override fun getFPlayersWithConsole(): List<FPlayer> = emptyList()
    override fun invalidate() = Unit
    override fun invalidate(uuid: UUID) = Unit
    override fun invalidateCache() = Unit
    override fun invalidateOfflineCache(uuid: UUID) = Unit
    override fun invalidateOnlineCache(uuid: UUID) = Unit
    override fun loadOnlineCache() = Unit
    override fun addConsole() = Unit
    override fun initialize(social: SocialService, flag: Boolean) = Unit
    override fun saveOrUpdate(uuid: UUID, name: String, type: String?, online: Boolean): FPlayer =
        FPlayer.UNKNOWN

    override fun addCache(fPlayer: FPlayer): FPlayer = fPlayer
    override fun updateCache(fPlayer: FPlayer): FPlayer = fPlayer
    override fun clearAndSave(fPlayer: FPlayer): FPlayer = fPlayer
}

/** builds a real fplayer from the api artifact with the minimum viable fields */
fun testFPlayer(uuid: UUID, id: Int = 1): FPlayer =
    FPlayerImpl.builder().uuid(uuid).name("player$id").id(id).online(true).type(FPlayer.PLAYER_TYPE).build()

/**
 * fp semantics (verified against the api source): date = issued-at epoch
 * millis, time = absolute expiry epoch millis or PERMANENT_TIME(-1).
 */
fun timedModeration(type: Moderation.Type, nowMillis: Long, expiresInMillis: Long = 90_000, reason: String? = "test"): Moderation =
    Moderation(1, 1, nowMillis - 10_000, nowMillis + expiresInMillis, reason, 0, type, true, "*")

fun permanentModeration(type: Moderation.Type, nowMillis: Long, reason: String? = "test"): Moderation =
    Moderation(1, 1, nowMillis - 10_000, Moderation.PERMANENT_TIME.toLong(), reason, 0, type, true, "*")

/** expired on arrival: ran out 5 seconds ago */
fun expiredModeration(type: Moderation.Type, nowMillis: Long): Moderation =
    Moderation(1, 1, nowMillis - 10_000, nowMillis - 5_000, "old", 0, type, true, "*")
