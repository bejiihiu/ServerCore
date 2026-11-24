package kz.bejiihiu.servercore.websocket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** message types on the wire; the ones we type plus the ones we carry raw */
enum class MessageType(val wire: String) {
    PLAYER_JOINED("PLAYER_JOINED"),
    PLAYER_LEFT("PLAYER_LEFT"),
    SERVER_STATUS("SERVER_STATUS"),
    SERVER_HEARTBEAT("SERVER_HEARTBEAT"),
    LINK_TOKEN("LINK_TOKEN"),
    SESSION_CHECK("SESSION_CHECK"),
    SESSION_CHECK_RESULT("SESSION_CHECK_RESULT"),
    ACCOUNT_DELETED("ACCOUNT_DELETED"),
    SPEC_STARTED("SPEC_STARTED"),
    SPEC_ENDED("SPEC_ENDED"),
    ACK("ACK"),
    ERROR("ERROR"),

    // backend -> plugin pushes we have no typed payload for yet. they decode
    // to Payload.Raw and fan out through GatewayListener.onWireMessage, so a
    // newer backend never kills the connection just because we don't speak
    // one of its types natively. GUEST_* travels this way too: the ws layer
    // owns that payload shape, the transport just relays it
    AUTH_REQUEST("AUTH_REQUEST"),
    AUTH_APPROVED("AUTH_APPROVED"),
    AUTH_REJECTED("AUTH_REJECTED"),
    AUTH_EXPIRED("AUTH_EXPIRED"),
    GUEST_STARTED("GUEST_STARTED"),
    GUEST_ENDED("GUEST_ENDED"),
    APPLICATION_SUBMITTED("APPLICATION_SUBMITTED"),
    APPLICATION_APPROVED("APPLICATION_APPROVED"),
    APPLICATION_REJECTED("APPLICATION_REJECTED"),
    LOCALE_PUSH("LOCALE_PUSH"),
    LOCALE_ACK("LOCALE_ACK"),
    /** catch-all for wire types this build has never heard of */
    UNKNOWN("UNKNOWN");

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun fromWire(value: String): MessageType? = byWire[value]
    }
}

/** how much a frame hurts when it dies together with the connection */
enum class Durability {
    /** stale by definition, a fresh one is on the way: heartbeats, acks */
    DROP,

    /** worth keeping across a blip, a crash may still lose it: presence, status, spec events */
    QUEUE,

    /** must survive a plugin restart: guest lifecycle + snapshots */
    DURABLE,
}

/** durability class per wire type; anything unlisted dies with the link */
fun durabilityOf(type: MessageType): Durability = when (type) {
    MessageType.GUEST_STARTED, MessageType.GUEST_ENDED -> Durability.DURABLE
    MessageType.PLAYER_JOINED, MessageType.PLAYER_LEFT, MessageType.SERVER_STATUS,
    MessageType.SPEC_STARTED, MessageType.SPEC_ENDED,
    -> Durability.QUEUE
    else -> Durability.DROP
}

/** typed payloads for the messages we send or act on */
sealed interface Payload {
    data class PlayerJoined(val minecraftUuid: String, val username: String) : Payload
    data class PlayerLeft(val minecraftUuid: String, val reason: String = "DISCONNECT") : Payload
    data class ServerStatus(
        val onlinePlayers: Int,
        val maxPlayers: Int,
        val version: String,
        val activeSpecSessions: Int? = null,
        /** ops lifecycle state name, see kz.bejiihiu.servercore.ops.ServerState */
        val state: String? = null,
        /** human-readable detail for the state, e.g. a restart reason */
        val detail: String? = null,
        /** true = fp's telegram module owns announcements right now, backend bot stays quiet */
        val telegramSuppressed: Boolean? = null,
    ) : Payload

    data class ServerHeartbeat(
        val onlinePlayers: Int,
        val maxPlayers: Int,
        val activeSpecSessions: Int? = null,
    ) : Payload
    data class LinkToken(val minecraftUuid: String, val username: String, val token: String, val expiresAt: Long) : Payload
    data class SessionCheck(val minecraftUuid: String) : Payload
    data class SessionCheckResult(val minecraftUuid: String, val online: Boolean, val sessionId: String? = null) : Payload
    data class AccountDeleted(val minecraftUuid: String, val reason: String) : Payload

    data class SpecStarted(
        val adminUuid: String,
        val targetUuid: String,
        val sessionId: String? = null,
        val adminUsername: String? = null,
        val targetUsername: String? = null,
    ) : Payload

    data class SpecEnded(
        val adminUuid: String,
        val targetUuid: String,
        val sessionId: String? = null,
        val adminUsername: String? = null,
        val targetUsername: String? = null,
        val durationSec: Long? = null,
        val reason: String = "NORMAL",
    ) : Payload
    data class Ack(val ackedId: String) : Payload
    data class Error(val code: String, val message: String) : Payload

    /**
     * a wire type we don't map to a typed payload: auth verdicts, guest
     * lifecycle, application events, locale pushes and anything a newer
     * backend invents. carried as parsed json; [wireType] keeps the real name
     * for UNKNOWN frames.
     */
    data class Raw(val wireType: String, val fields: JsonObject) : Payload
}

/** one wire message: envelope meta + typed payload, hmac set after signing */
data class Envelope(
    val id: String,
    val type: MessageType,
    val timestamp: Long,
    val payload: Payload,
    val correlationId: String? = null,
)

/** exceptions the gateway layer raises; connection failures land here */
class ProtocolException(message: String) : Exception(message)

/**
 * codec for the backend gateway protocol: canonical-json signing, envelope
 * encode/decode with hmac verification. the canonical form is byte-for-byte
 * identical to the backend's `canonicalize` — sorted keys at every level,
 * compact separators, no whitespace.
 */
class EnvelopeCodec(private val secret: String) {
    private val json = Json {
        ignoreUnknownKeys = true
    }
    private val random = SecureRandom()

    /** unique message ids matching the backend's ^[A-Za-z0-9_-]{8,64}$ pattern */
    fun newMessageId(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun sign(envelope: Envelope): String {
        val meta = buildJsonObject {
            put("id", envelope.id)
            put("type", envelope.type.wire)
            put("timestamp", envelope.timestamp)
            envelope.correlationId?.let { put("correlationId", it) }
            put("payload", payloadAsJson(envelope.payload))
        }
        return hmac(canonicalize(meta))
    }

    fun encode(envelope: Envelope): String {
        val meta = buildJsonObject {
            put("id", envelope.id)
            put("type", envelope.type.wire)
            put("timestamp", envelope.timestamp)
            envelope.correlationId?.let { put("correlationId", it) }
            put("payload", payloadAsJson(envelope.payload))
        }
        // signed envelope = meta + hmac, serialized canonically (same key order)
        val signed = buildJsonObject {
            meta.forEach { (key, value) -> put(key, value) }
            put("hmac", sign(envelope))
        }
        return canonicalize(signed)
    }

    /** parses and hmac-verifies an inbound frame; throws ProtocolException on any mismatch */
    fun decode(raw: String, now: Long, maxSkewMs: Long, maxPacketBytes: Int): DecodedMessage {
        if (raw.toByteArray(Charsets.UTF_8).size > maxPacketBytes) {
            throw ProtocolException("packet exceeds size limit")
        }
        val root = json.parseToJsonElement(raw) as? JsonObject
            ?: throw ProtocolException("message is not an object")
        val hmacValue = (root["hmac"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ProtocolException("missing hmac")
        val id = root.string("id") ?: throw ProtocolException("missing id")
        val typeWire = root.string("type") ?: throw ProtocolException("missing type")
        val timestamp = root.long("timestamp") ?: throw ProtocolException("missing timestamp")
        val correlationId = root.string("correlationId")
        val payloadElement = root["payload"] ?: throw ProtocolException("missing payload")

        val type = MessageType.fromWire(typeWire)
        if (kotlin.math.abs(now - timestamp) > maxSkewMs) {
            throw ProtocolException("stale timestamp")
        }

        // verify over the envelope minus the hmac field
        val withoutHmac = buildJsonObject {
            root.forEach { (key, value) -> if (key != "hmac") put(key, value) }
        }
        val expected = hmac(canonicalize(withoutHmac))
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), hmacValue.toByteArray(Charsets.UTF_8))) {
            throw ProtocolException("bad hmac")
        }

        val payloadObject = payloadElement as? JsonObject
            ?: throw ProtocolException("payload is not an object")
        // an unknown type is a version skew, not a forgery: the hmac verified,
        // so accept the frame as raw json and keep the socket alive
        val payload: Payload = if (type == null) {
            Payload.Raw(typeWire, payloadObject)
        } else {
            payloadFromJson(type, payloadObject)
        }
        val resolvedType = type ?: MessageType.UNKNOWN
        return DecodedMessage(Envelope(id, resolvedType, timestamp, payload, correlationId), payloadObject)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    /** payload as a json object; also the relay format for the ws layer */
    fun payloadAsJson(payload: Payload): JsonElement = when (payload) {
        is Payload.PlayerJoined -> buildJsonObject {
            put("minecraftUuid", payload.minecraftUuid)
            put("username", payload.username)
        }
        is Payload.PlayerLeft -> buildJsonObject {
            put("minecraftUuid", payload.minecraftUuid)
            put("reason", payload.reason)
        }
        is Payload.ServerStatus -> buildJsonObject {
            put("onlinePlayers", payload.onlinePlayers)
            put("maxPlayers", payload.maxPlayers)
            put("version", payload.version)
            payload.activeSpecSessions?.let { put("activeSpecSessions", it) }
            payload.state?.let { put("state", it) }
            payload.detail?.let { put("detail", it) }
            payload.telegramSuppressed?.let { put("telegramSuppressed", it) }
        }
        is Payload.ServerHeartbeat -> buildJsonObject {
            put("onlinePlayers", payload.onlinePlayers)
            put("maxPlayers", payload.maxPlayers)
            payload.activeSpecSessions?.let { put("activeSpecSessions", it) }
        }
        is Payload.LinkToken -> buildJsonObject {
            put("minecraftUuid", payload.minecraftUuid)
            put("username", payload.username)
            put("token", payload.token)
            put("expiresAt", payload.expiresAt)
        }
        is Payload.SessionCheck -> buildJsonObject {
            put("minecraftUuid", payload.minecraftUuid)
        }
        is Payload.SessionCheckResult -> buildJsonObject {
            put("minecraftUuid", payload.minecraftUuid)
            put("online", payload.online)
            payload.sessionId?.let { put("sessionId", it) }
        }
        is Payload.AccountDeleted -> buildJsonObject {
            put("minecraftUuid", payload.minecraftUuid)
            put("reason", payload.reason)
        }
        is Payload.SpecStarted -> buildJsonObject {
            put("adminUuid", payload.adminUuid)
            put("targetUuid", payload.targetUuid)
            payload.sessionId?.let { put("sessionId", it) }
            payload.adminUsername?.let { put("adminUsername", it) }
            payload.targetUsername?.let { put("targetUsername", it) }
        }
        is Payload.SpecEnded -> buildJsonObject {
            put("adminUuid", payload.adminUuid)
            put("targetUuid", payload.targetUuid)
            payload.sessionId?.let { put("sessionId", it) }
            payload.adminUsername?.let { put("adminUsername", it) }
            payload.targetUsername?.let { put("targetUsername", it) }
            payload.durationSec?.let { put("durationSec", it) }
            put("reason", payload.reason)
        }
        is Payload.Ack -> buildJsonObject {
            put("ackedId", payload.ackedId)
        }
        is Payload.Error -> buildJsonObject {
            put("code", payload.code)
            put("message", payload.message)
        }
        is Payload.Raw -> payload.fields
    }

    private fun payloadFromJson(type: MessageType, element: JsonElement): Payload {
        val obj = element as? JsonObject ?: throw ProtocolException("payload is not an object")
        return when (type) {
            MessageType.PLAYER_JOINED -> Payload.PlayerJoined(
                obj.stringOrThrow("minecraftUuid"),
                obj.stringOrThrow("username"),
            )
            MessageType.PLAYER_LEFT -> Payload.PlayerLeft(
                obj.stringOrThrow("minecraftUuid"),
                obj.stringOrThrow("reason"),
            )
            MessageType.SERVER_STATUS -> Payload.ServerStatus(
                obj.intOrThrow("onlinePlayers"),
                obj.intOrThrow("maxPlayers"),
                obj.stringOrThrow("version"),
                obj.int("activeSpecSessions"),
                obj.string("state"),
                obj.string("detail"),
                obj.bool("telegramSuppressed"),
            )
            MessageType.SERVER_HEARTBEAT -> Payload.ServerHeartbeat(
                obj.intOrThrow("onlinePlayers"),
                obj.intOrThrow("maxPlayers"),
            )
            MessageType.LINK_TOKEN -> Payload.LinkToken(
                obj.stringOrThrow("minecraftUuid"),
                obj.stringOrThrow("username"),
                obj.stringOrThrow("token"),
                obj.longOrThrow("expiresAt"),
            )
            MessageType.SESSION_CHECK -> Payload.SessionCheck(obj.stringOrThrow("minecraftUuid"))
            MessageType.SESSION_CHECK_RESULT -> Payload.SessionCheckResult(
                obj.stringOrThrow("minecraftUuid"),
                obj.boolOrThrow("online"),
                obj.string("sessionId"),
            )
            MessageType.ACCOUNT_DELETED -> Payload.AccountDeleted(
                obj.stringOrThrow("minecraftUuid"),
                obj.stringOrThrow("reason"),
            )
            MessageType.SPEC_STARTED -> Payload.SpecStarted(
                obj.stringOrThrow("adminUuid"),
                obj.stringOrThrow("targetUuid"),
                obj.string("sessionId"),
                obj.string("adminUsername"),
                obj.string("targetUsername"),
            )
            MessageType.SPEC_ENDED -> Payload.SpecEnded(
                obj.stringOrThrow("adminUuid"),
                obj.stringOrThrow("targetUuid"),
                obj.string("sessionId"),
                obj.string("adminUsername"),
                obj.string("targetUsername"),
                obj.long("durationSec"),
                obj.string("reason") ?: "NORMAL",
            )
            MessageType.ACK -> Payload.Ack(obj.stringOrThrow("ackedId"))
            MessageType.ERROR -> Payload.Error(obj.stringOrThrow("code"), obj.stringOrThrow("message"))

            // backend pushes without typed payloads yet: auth verdicts, guest
            // lifecycle (the ws layer owns that shape), application events,
            // locale pushes, audit notifications. the raw json travels as-is
            // and typed consumers appear later
            MessageType.AUTH_REQUEST,
            MessageType.AUTH_APPROVED,
            MessageType.AUTH_REJECTED,
            MessageType.AUTH_EXPIRED,
            MessageType.GUEST_STARTED,
            MessageType.GUEST_ENDED,
            MessageType.APPLICATION_SUBMITTED,
            MessageType.APPLICATION_APPROVED,
            MessageType.APPLICATION_REJECTED,
            MessageType.LOCALE_PUSH,
            MessageType.LOCALE_ACK,
            MessageType.UNKNOWN,
            -> Payload.Raw(type.wire, obj)
        }
    }

    private fun JsonObject.stringOrThrow(key: String): String =
        string(key) ?: throw ProtocolException("payload field $key missing or not a string")

    private fun JsonObject.longOrThrow(key: String): Long =
        long(key) ?: throw ProtocolException("payload field $key missing or not a number")

    private fun JsonObject.intOrThrow(key: String): Int =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: throw ProtocolException("payload field $key missing or not a number")

    private fun JsonObject.boolOrThrow(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?: throw ProtocolException("payload field $key missing or not a boolean")

    /** hmac-sha256 hex over the canonical string, matching the backend byte for byte */
    private fun hmac(canonical: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * canonical json: object keys sorted recursively, compact separators.
         * mirrors the backend's canonicalize() exactly.
         */
        fun canonicalize(element: JsonElement): String = when (element) {
            is JsonPrimitive -> primitiveToString(element)
            is kotlinx.serialization.json.JsonArray ->
                element.joinToString(",", "[", "]") { canonicalize(it) }
            is JsonObject ->
                element.entries
                    .sortedWith(compareBy { it.key })
                    .joinToString(",", "{", "}") { (key, value) ->
                        "${jsonEscape(key)}:${canonicalize(value)}"
                    }
        }

        private fun primitiveToString(element: JsonPrimitive): String = when {
            element.isString -> jsonEscape(element.content)
            else -> element.content // numbers/booleans/null keep their literal form
        }

        /** json string escaping identical to JSON.stringify for our content */
        private fun jsonEscape(value: String): String {
            val sb = StringBuilder(value.length + 2)
            sb.append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else ->
                        if (ch < ' ') {
                            sb.append("\\u").append(String.format("%04x", ch.code))
                        } else {
                            sb.append(ch)
                        }
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}

/** a decoded, hmac-verified inbound message, plus the raw payload json */
data class DecodedMessage(
    val envelope: Envelope,
    /** the payload exactly as it arrived, for raw fan-out to other consumers */
    val rawPayload: JsonObject,
)
