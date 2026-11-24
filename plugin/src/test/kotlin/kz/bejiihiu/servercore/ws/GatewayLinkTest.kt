package kz.bejiihiu.servercore.ws

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.bejiihiu.servercore.websocket.DurableOutbox
import kz.bejiihiu.servercore.websocket.GatewayClient
import kz.bejiihiu.servercore.websocket.GatewayConfig
import kz.bejiihiu.servercore.websocket.ServerStatsProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * the bridge between the guest module's envelope hub and the signed gateway
 * transport: sends park durably when the link is down, ride the wire when it
 * is up, and unknown types are rejected instead of silently vanishing.
 */
class GatewayLinkTest {

    private val dir = Files.createTempDirectory("servercore-link-test")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        Files.deleteIfExists(dir)
    }

    private class Harness(val link: GatewayLink, val outbox: DurableOutbox, val client: GatewayClient)

    private fun harness(): Harness {
        val outbox = DurableOutbox(dir.resolve("outbox.json"), Runnable::run)
        outbox.load()
        val clock = AtomicLong(1_700_000_000_000)
        val client = GatewayClient(
            config = GatewayConfig(
                url = "ws://127.0.0.1:9/ws",
                serverId = "game-1",
                secret = "0123456789abcdef0123456789abcdef",
            ),
            listener = object : kz.bejiihiu.servercore.websocket.GatewayListener {},
            scheduler = { _, _ -> },
            stats = object : ServerStatsProvider {
                override fun onlinePlayers() = 0
                override fun maxPlayers() = 20
                override fun serverVersion() = "1.21"
            },
            presenceLookup = { false },
            durablePark = outbox,
            now = { clock.get() },
        )
        return Harness(GatewayLink(client), outbox, client)
    }

    private fun guestStarted(sessionId: String) = messageEnvelope(
        MessageTypes.GUEST_STARTED,
        GuestStartedPayload(
            minecraftUuid = "11111111-1111-4111-8111-111111111111",
            username = "steve",
            sessionId = sessionId,
            snapshot = SnapshotPayload(
                world = "world", x = 0.0, y = 64.0, z = 0.0,
                yaw = 0.0, pitch = 0.0, gameMode = "SURVIVAL",
                flying = false, flySpeed = 0.1, walkSpeed = 0.2,
            ),
        ),
    )

    @Test
    fun `guest started parks durably while the link is down, keyed by sessionId+type`() {
        val h = harness()
        assertFalse(h.link.connected)

        assertTrue(h.link.send(guestStarted("guest-abc123")))
        assertEquals(1, h.outbox.size())
        assertEquals("GUEST_STARTED|guest-abc123", h.outbox.snapshot().single().key)
    }

    @Test
    fun `a redelivered guest start replaces its pending twin instead of piling up`() {
        val h = harness()
        h.link.send(guestStarted("guest-abc123"))
        h.link.send(guestStarted("guest-abc123"))
        assertEquals(1, h.outbox.size())
    }

    @Test
    fun `unknown wire types are rejected, not parked`() {
        val h = harness()
        assertFalse(h.link.send(messageEnvelope("WARP_DRIVE", buildJsonObject { put("x", 1) })))
        assertEquals(0, h.outbox.size())
    }

    @Test
    fun `the hub goes live with the link and dead without it`() {
        val h = harness()
        val hub = BackendGateway()
        var sawConnected = false
        hub.addListener(object : GatewayListener {
            override fun onMessage(envelope: WsEnvelope) {}
            override fun onConnected() {
                sawConnected = true
            }
        })
        assertFalse(hub.connected)

        // bring the transport up so the link reports connected
        val socket = kz.bejiihiu.servercore.websocket.FakeWebSocket()
        h.client.attachForTest(socket)
        h.client.onOpen(socket)

        hub.attach(h.link)
        assertTrue(hub.connected)
        assertTrue(sawConnected)

        hub.detach(h.link)
        assertFalse(hub.connected)
    }
}
