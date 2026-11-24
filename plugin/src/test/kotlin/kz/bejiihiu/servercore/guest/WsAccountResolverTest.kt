package kz.bejiihiu.servercore.guest

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kz.bejiihiu.servercore.ws.BackendGateway
import kz.bejiihiu.servercore.ws.BackendLink
import kz.bejiihiu.servercore.ws.AuthDecisionPayload
import kz.bejiihiu.servercore.ws.AuthRequestPayload
import kz.bejiihiu.servercore.ws.MessageTypes
import kz.bejiihiu.servercore.ws.WsEnvelope
import kz.bejiihiu.servercore.ws.messageEnvelope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows

/**
 * the ws-side race rules: one auth request per uuid no matter how many
 * clients join with it, and a telegram approval that fires its hook even when
 * no resolution is waiting anymore (the approval-wins path).
 */
class WsAccountResolverTest {

    @TempDir
    lateinit var dir: Path

    private fun newCache() = LocalAccountCache(dir.resolve("account-cache.json"), Runnable::run)

    private fun config(linkTtlMs: Long = 90_000) = GuestConfig(
        enabled = true,
        restoreOnRestart = true,
        resolveTimeoutMs = 5_000,
        sessionLimit = 3,
        kickAfterLimit = true,
        reminderIntervalSeconds = 60,
        gamemodeSweepSeconds = 2,
        commandWhitelist = setOf("help"),
        botUsername = "ServerCoreBot",
        linkTtlMs = linkTtlMs,
        snapshotStoragePath = "guest-snapshots.json",
        lang = "en",
        restrictions = RestrictionToggles.allRestricted(),
        localAccounts = emptyMap(),
    )

    private class RecordingLink(private val up: Boolean = true) : BackendLink {
        override val connected: Boolean = up
        val sent = CopyOnWriteArrayList<String>()

        override fun send(envelope: WsEnvelope): Boolean {
            if (!up) return false
            sent.add(envelope.type)
            return true
        }
    }

    private fun authRequest(uuid: UUID, link: String? = "https://t.me/Bot?start=tok") =
        messageEnvelope(
            MessageTypes.AUTH_REQUEST,
            AuthRequestPayload(
                minecraftUuid = uuid.toString(),
                username = "player",
                sessionId = "guest-abc",
                link = link,
            ),
        )

    private fun authDecision(type: String, uuid: UUID) =
        messageEnvelope(type, AuthDecisionPayload(minecraftUuid = uuid.toString(), sessionId = "guest-abc"))

    @Test
    fun `two simultaneous joins share a single auth request`() {
        val gateway = BackendGateway()
        val link = RecordingLink()
        gateway.attach(link)
        val resolver = WsAccountResolver(gateway, newCache(), config())
        val uuid = UUID.randomUUID()

        val pool = Executors.newFixedThreadPool(4)
        val barrier = CyclicBarrier(4)
        val futures = (1..4).map {
            pool.submit(Callable { resolver.resolve(uuid, "player") })
        }
        val first = futures[0].get()
        futures.drop(1).forEach { assertSame(first, it.get(), "duplicate joins ride the in-flight check") }
        pool.shutdown()

        assertEquals(1, link.sent.count { it == MessageTypes.PLAYER_JOINED }, "one uuid, one auth request")
    }

    @Test
    fun `every waiter gets the verdict the backend answers`() {
        val gateway = BackendGateway()
        gateway.attach(RecordingLink())
        val resolver = WsAccountResolver(gateway, newCache(), config())
        val uuid = UUID.randomUUID()

        val a = resolver.resolve(uuid, "player")
        val b = resolver.resolve(uuid, "player")
        resolver.onMessage(authRequest(uuid, "https://t.me/Bot?start=xyz"))

        assertEquals(AccountStatus.GuestPendingLogin("https://t.me/Bot?start=xyz"), a.get(1, TimeUnit.SECONDS))
        assertEquals(a.get(), b.get())
    }

    @Test
    fun `a second join after the first resolved sends a fresh auth request`() {
        val gateway = BackendGateway()
        val link = RecordingLink()
        gateway.attach(link)
        val resolver = WsAccountResolver(gateway, newCache(), config())
        val uuid = UUID.randomUUID()

        val first = resolver.resolve(uuid, "player")
        resolver.onMessage(authRequest(uuid))
        first.get(1, TimeUnit.SECONDS)

        resolver.resolve(uuid, "player")
        assertEquals(2, link.sent.count { it == MessageTypes.PLAYER_JOINED }, "a new join is a new check")
    }

    @Test
    fun `approval fires its hook even when nothing is waiting anymore`() {
        val gateway = BackendGateway()
        gateway.attach(RecordingLink())
        val cache = newCache()
        val resolver = WsAccountResolver(gateway, cache, config())
        val uuid = UUID.randomUUID()
        var approved: UUID? = null
        resolver.onAuthApproved { approved = it }

        // late approval: no resolve in flight at all, exactly the
        // approval-during-session-start shape the service must survive
        resolver.onMessage(authDecision(MessageTypes.AUTH_APPROVED, uuid))

        assertEquals(uuid, approved)
        assertEquals(AccountStateMapper.STATE_ACTIVE, cache.get(uuid)?.status)
    }

    @Test
    fun `rejected and expired release the waiter back to guest`() {
        val gateway = BackendGateway()
        gateway.attach(RecordingLink())
        val resolver = WsAccountResolver(gateway, newCache(), config())
        val uuid = UUID.randomUUID()

        val future = resolver.resolve(uuid, "player")
        resolver.onMessage(authDecision(MessageTypes.AUTH_REJECTED, uuid))

        assertEquals(AccountStatus.GuestPendingLogin(null), future.get(1, TimeUnit.SECONDS))
    }

    @Test
    fun `link down mid-join fails closed and sends nothing`() {
        val gateway = BackendGateway()
        gateway.attach(RecordingLink(up = false))
        val resolver = WsAccountResolver(gateway, newCache(), config())
        val uuid = UUID.randomUUID()

        val future = resolver.resolve(uuid, "player")

        assertThrows<Exception> { future.get(1, TimeUnit.SECONDS) }
        assertTrue(future.isCompletedExceptionally)
        assertFalse(gateway.connected)
    }

    @Test
    fun `a pending link only counts while it is fresh`() {
        val gateway = BackendGateway()
        gateway.attach(RecordingLink())
        val resolver = WsAccountResolver(gateway, newCache(), config(linkTtlMs = -1))
        val uuid = UUID.randomUUID()
        resolver.onMessage(authRequest(uuid, "https://t.me/Bot?start=old"))

        assertNull(resolver.pendingLink(uuid), "an expired one-time token is not handed out again")
    }
}
