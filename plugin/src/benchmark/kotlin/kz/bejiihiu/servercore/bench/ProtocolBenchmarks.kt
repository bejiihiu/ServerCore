package kz.bejiihiu.servercore.bench

import kz.bejiihiu.servercore.websocket.Envelope
import kz.bejiihiu.servercore.websocket.EnvelopeCodec
import kz.bejiihiu.servercore.websocket.MessageType
import kz.bejiihiu.servercore.websocket.Payload
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * the plugin side of the wire codec: canonical json, hmac signing and
 * envelope parse. these run once per outbound/inbound frame, so they are not
 * the hottest loop in the plugin, but a heartbeat plus presence traffic is
 * per-second-per-server and the hmac path is fully on the ws thread.
 *
 * no backend comparison here: the node implementation lives in another
 * runtime and a cross-runtime number would say nothing about the plugin's
 * own cost profile.
 */
object ProtocolBenchmarks {

    fun run() {
        Harness.section("websocket / envelope codec")

        val codec = EnvelopeCodec("bench-secret-0123456789abcdef")
        val status = Envelope(
            id = "srvstatus0001",
            type = MessageType.SERVER_STATUS,
            timestamp = NOW,
            payload = Payload.ServerStatus(
                onlinePlayers = 37,
                maxPlayers = 200,
                version = "26.3",
                activeSpecSessions = 2,
                state = "ONLINE",
                detail = null,
                telegramSuppressed = false,
            ),
        )
        val statusWire = codec.encode(status)
        val statusPayload = codec.payloadAsJson(status.payload)

        val localePush = Envelope(
            id = "localepush0001",
            type = MessageType.LOCALE_PUSH,
            timestamp = NOW,
            payload = Payload.Raw(
                "LOCALE_PUSH",
                buildJsonObject {
                    put("locale", "ru")
                    put("revision", "2026-11-25T10:00:00.000Z")
                    put("content", "bars:\n  ram:\n    title: \"<primary>RAM</primary>\"\n")
                    put("hash", "9f2c1ab34d5e6f708192a3b4c5d6e7f8")
                },
            ),
        )
        val localeWire = codec.encode(localePush)

        Harness.bench("encode SERVER_STATUS", note = "payload + canonical + hmac") { codec.encode(status) }
        Harness.bench("decode SERVER_STATUS", note = "size check + parse + hmac verify") {
            codec.decode(statusWire, NOW + 100, MAX_SKEW, MAX_BYTES)
        }
        Harness.bench("encode LOCALE_PUSH (raw)", note = "raw payload passes through") { codec.encode(localePush) }
        Harness.bench("decode LOCALE_PUSH (raw)") { codec.decode(localeWire, NOW + 100, MAX_SKEW, MAX_BYTES) }
        Harness.bench("canonicalize payload") { EnvelopeCodec.canonicalize(statusPayload) }
        Harness.bench("newMessageId", note = "securerandom + base64 url") { codec.newMessageId() }
    }

    private const val NOW = 1_700_000_000_000L
    private const val MAX_SKEW = 30_000L
    private const val MAX_BYTES = 262_144
}
