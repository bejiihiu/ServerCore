package kz.bejiihiu.servercore.ws

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** wire message types from the documented backend protocol */
object MessageTypes {
    const val PLAYER_JOINED = "PLAYER_JOINED"
    const val PLAYER_LEFT = "PLAYER_LEFT"
    const val AUTH_REQUEST = "AUTH_REQUEST"
    const val AUTH_APPROVED = "AUTH_APPROVED"
    const val AUTH_REJECTED = "AUTH_REJECTED"
    const val AUTH_EXPIRED = "AUTH_EXPIRED"
    const val GUEST_STARTED = "GUEST_STARTED"
    const val GUEST_ENDED = "GUEST_ENDED"
    const val APPLICATION_SUBMITTED = "APPLICATION_SUBMITTED"
    const val APPLICATION_APPROVED = "APPLICATION_APPROVED"
    const val APPLICATION_REJECTED = "APPLICATION_REJECTED"
    const val APPLICATION_EXPIRED = "APPLICATION_EXPIRED"
    const val ACCOUNT_DELETED = "ACCOUNT_DELETED"
    const val LINK_TOKEN = "LINK_TOKEN"
}

/** shared json setup: tolerate extra fields so backend additions never break us */
val wsJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/** single envelope shape, identical in both directions */
@Serializable
data class WsEnvelope(
    val id: String,
    val type: String,
    val timestamp: Long,
    val correlationId: String? = null,
    val payload: JsonObject,
)

@Serializable
data class PlayerJoinedPayload(
    val minecraftUuid: String,
    val username: String,
    val serverId: String? = null,
)

@Serializable
data class PlayerLeftPayload(
    val minecraftUuid: String,
    val username: String? = null,
    val reason: String = "DISCONNECT",
)

@Serializable
data class AuthRequestPayload(
    val minecraftUuid: String,
    val username: String,
    val sessionId: String,
    // optional backend extension: the personal deep link for the guest prompt.
    // when absent the plugin falls back to the generic bot url from config
    val link: String? = null,
    val token: String? = null,
    val botUsername: String? = null,
)

@Serializable
data class AuthDecisionPayload(
    val minecraftUuid: String,
    val sessionId: String,
    val reason: String? = null,
)

@Serializable
data class AccountDeletedPayload(
    val minecraftUuid: String,
    val reason: String,
)

// admission fan-outs, backend -> plugin, informational (placeholders + logs)

@Serializable
data class ApplicationSubmittedPayload(
    val applicationId: String,
    val minecraftUuid: String,
    val telegramId: String,
)

@Serializable
data class ApplicationApprovedPayload(
    val applicationId: String,
    val minecraftUuid: String,
)

@Serializable
data class ApplicationRejectedPayload(
    val applicationId: String,
    val minecraftUuid: String,
    val reason: String? = null,
)

@Serializable
data class ApplicationExpiredPayload(
    val applicationId: String,
    val minecraftUuid: String,
)

/**
 * account linking: a fresh one-time deep-link token for a joined player.
 * the guest prompt turns it into a personal t.me/<bot>?start=<token> url so
 * the player can actually reach the linking conversation from the game chat.
 */
@Serializable
data class LinkTokenPayload(
    val minecraftUuid: String,
    val username: String,
    val token: String,
    val expiresAt: Long,
)

// guest payloads live here too because the wire format is the contract, not an
// implementation detail of one module

@Serializable
data class GuestStartedPayload(
    val minecraftUuid: String,
    val username: String,
    val sessionId: String,
    val snapshot: SnapshotPayload,
)

@Serializable
data class GuestEndedPayload(
    val minecraftUuid: String,
    val sessionId: String,
    val reason: String,
)

/** wire shape of the guest snapshot, mirrors the backend zod schema field by field */
@Serializable
data class SnapshotPayload(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Double,
    val pitch: Double,
    val gameMode: String,
    val flying: Boolean,
    val flySpeed: Double,
    val walkSpeed: Double,
)

/** 32 hex chars, satisfies the envelope id pattern on both ends */
fun newMessageId(): String = UUID.randomUUID().toString().replace("-", "")

/** session ids must match the backend pattern [a-z0-9._-]{1,64} */
fun newSessionId(): String = "guest-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)

inline fun <reified T> messageEnvelope(type: String, payload: T, correlationId: String? = null): WsEnvelope =
    WsEnvelope(
        id = newMessageId(),
        type = type,
        timestamp = System.currentTimeMillis(),
        correlationId = correlationId,
        payload = wsJson.encodeToJsonElement(payload).jsonObject,
    )

/** decode a payload, serializer derived from the concrete type at the call site */
inline fun <reified T> WsEnvelope.decodePayload(): T =
    wsJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), payload)
