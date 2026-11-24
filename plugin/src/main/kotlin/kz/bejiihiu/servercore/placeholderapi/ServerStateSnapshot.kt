package kz.bejiihiu.servercore.placeholderapi

import java.util.UUID

/**
 * last-known facts about one player, served to placeholders from memory only.
 * every field here was learned from a websocket event, a join/quit event or
 * the durable account cache — nothing is ever fetched on demand.
 */
data class PlayerStateSnapshot(
    val uuid: UUID,
    val username: String,
    /** last known backend account state (ACTIVE, BANNED, ...), null = never heard */
    val accountStatus: String?,
    /** backend confirmed the telegram login at least once */
    val authApproved: Boolean,
    /** an auth request is waiting for a telegram confirmation right now */
    val authPending: Boolean,
    /** last known admission verdict (VOTING, APPROVED, REJECTED), null = none seen */
    val applicationStatus: String?,
    /** the backend told us this account is bound to a telegram identity */
    val telegramLinked: Boolean,
    val online: Boolean,
    /** epoch ms the current session started, null when offline */
    val sessionStartAt: Long?,
    /** epoch ms of the last join or quit we saw locally, null = never */
    val lastSeenAt: Long?,
    /** freshness anchor for backend-derived facts, null = no backend facts yet */
    val backendUpdatedAt: Long?,
    /** freshness anchor for everything (eviction + general ordering) */
    val updatedAt: Long,
)

/**
 * the read seam every placeholder goes through. implementations must answer
 * from in-memory state only and never block the caller — placeholders render
 * on whatever thread the asking plugin uses, including region threads.
 */
interface ServerStateSnapshotProvider {

    /** null = this server has never seen the player */
    fun snapshot(uuid: UUID): PlayerStateSnapshot?

    /** same as [snapshot], looked up by the last known username */
    fun snapshotByName(username: String): PlayerStateSnapshot?

    /** liveness of the backend link as the state holder sees it */
    fun backendOnline(): Boolean

    /** the state holder's clock, so consumers compute freshness consistently */
    fun now(): Long
}
