package kz.bejiihiu.servercore.ws

import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kz.bejiihiu.servercore.ops.OpsEventSink
import kz.bejiihiu.servercore.ops.ServerStatusPayload

/**
 * ops bus -> websocket bridge: serializes a [ServerStatusPayload] and hands it
 * to the gateway under the SERVER_STATUS wire type. the gateway parks QUEUE
 * frames while the link is down, so an ops event published during a blip is
 * replayed (in order) on reconnect instead of being lost — the backend keeps
 * seeing state flips even across a short outage.
 *
 * the send seam is a function, not the GatewayClient itself, so the payload
 * mapping is unit-testable without a socket.
 */
class OpsStatusSink(
    private val send: (id: String, wireType: String, payload: JsonObject, correlationId: String?) -> Boolean,
) : OpsEventSink {

    override fun publish(state: kz.bejiihiu.servercore.ops.ServerState, detail: String?, payload: ServerStatusPayload) {
        val json = wsJson.encodeToJsonElement(ServerStatusPayload.serializer(), payload).jsonObject
        send(newMessageId(), MessageTypes.SERVER_STATUS, json, null)
    }
}
