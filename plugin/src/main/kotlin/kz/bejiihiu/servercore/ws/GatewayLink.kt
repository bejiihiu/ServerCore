package kz.bejiihiu.servercore.ws

import kz.bejiihiu.servercore.websocket.GatewayClient

/**
 * the real BackendLink: bridges the guest module's envelope hub onto the
 * signed gateway transport. sends hand the envelope to the gateway client,
 * which tracks the ack, retries bounded and parks durable frames (guest
 * lifecycle) across reconnects and plugin restarts — a downed link never
 * loses a GUEST_STARTED.
 *
 * the incoming side is wired by the plugin: gateway inbound frames are
 * fanned into [BackendGateway.receive] as WsEnvelope objects.
 */
class GatewayLink(private val client: GatewayClient) : BackendLink {

    override val connected: Boolean
        get() = client.isConnected()

    override fun send(envelope: WsEnvelope): Boolean =
        client.sendRaw(envelope.id, envelope.type, envelope.payload, envelope.correlationId)
}
