package kz.bejiihiu.servercore.spec

import java.util.Optional
import java.util.UUID
import kz.bejiihiu.servercore.protection.AllowedGamemodeOverrideRegistry
import kz.bejiihiu.servercore.spectator.SpecSessionReader
import org.bukkit.GameMode
import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecSessionBridgesTest {

    private val admin = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
    private val target = UUID.fromString("1b4b2f2e-1c2d-3e4f-5a6b-7c8d9e0f1a2b")

    private fun session(saved: SavedAdminState? = null): SpecSession = SpecSession(
        adminId = admin,
        adminName = "Admin",
        targetId = target,
        targetName = "Target",
        sessionId = "spec-1",
        savedState = saved,
    )

    private fun saved(gamemode: GameMode) = SavedAdminState(
        gamemode = gamemode,
        location = Location(null, 0.0, 64.0, 0.0),
        flying = false,
        allowFlight = false,
        walkSpeed = 0.2f,
        flySpeed = 0.1f,
    )

    // ---- gamemode sanction bridge ----

    @Test
    fun `claim sanctions spectator before the world touches the mode`() {
        val registry = AllowedGamemodeOverrideRegistry()
        val bridge = GamemodeSanctionBridge(registry)

        bridge.onClaimed(session())

        assertTrue(registry.isSanctioned(admin, GameMode.SPECTATOR))
        // no blanket sanction: other modes stay guarded
        assertFalse(registry.isSanctioned(admin, GameMode.CREATIVE))
    }

    @Test
    fun `end revokes the session grant and sanctions the saved mode briefly`() {
        val registry = AllowedGamemodeOverrideRegistry()
        val bridge = GamemodeSanctionBridge(registry)
        val s = session(saved(GameMode.CREATIVE))
        bridge.onClaimed(s)

        bridge.onEnded(s, ExitReason.COMMAND)

        // session grant gone, restore grant alive for the async restore event
        assertFalse(registry.isSanctioned(admin, GameMode.SPECTATOR))
        assertTrue(registry.isSanctioned(admin, GameMode.CREATIVE))
    }

    @Test
    fun `restore sanction expires on its own`() {
        var now = 0L
        val registry = AllowedGamemodeOverrideRegistry { now }
        val bridge = GamemodeSanctionBridge(registry)
        val s = session(saved(GameMode.CREATIVE))
        bridge.onClaimed(s)
        bridge.onEnded(s, ExitReason.COMMAND)

        now += 61_000
        registry.sweep()
        assertFalse(registry.isSanctioned(admin, GameMode.CREATIVE))
    }

    @Test
    fun `abort releases the sanction the same way`() {
        val registry = AllowedGamemodeOverrideRegistry()
        val bridge = GamemodeSanctionBridge(registry)
        val s = session(saved(GameMode.SURVIVAL))
        bridge.onClaimed(s)

        bridge.onAborted(s)

        // survival needs no grant, so nothing is sanctioned after the abort
        assertTrue(registry.sanctionedModes(admin).isEmpty())
    }

    // ---- session-bound reader ----

    @Test
    fun `the reader answers from the session phase`() {
        val s = session()
        val reader: SpecSessionReader = SessionBoundReader(s)

        assertEquals(Optional.of(target), reader.currentTarget())

        s.phase = SessionPhase.ENDING
        assertEquals(Optional.empty<UUID>(), reader.currentTarget())
    }

    // ---- inventory viewer bridge ----

    private class RecordingViews : SpecInventoryViews {
        var opened: Pair<UUID, UUID>? = null
        var reader: SpecSessionReader? = null
        var closed: Pair<UUID, String>? = null

        override fun open(adminId: UUID, targetId: UUID, reader: SpecSessionReader) {
            opened = adminId to targetId
            this.reader = reader
        }

        override fun close(adminId: UUID, reason: String) {
            closed = adminId to reason
        }
    }

    @Test
    fun `the viewer opens with the session and closes with it`() {
        val views = RecordingViews()
        val bridge = InventoryViewerBridge(views)
        val s = session()

        bridge.onStarted(s)
        assertEquals(admin to target, views.opened)
        // the keep-alive reader points at the session's target
        assertEquals(Optional.of(target), views.reader?.currentTarget())

        bridge.onEnded(s, ExitReason.COMMAND)
        assertEquals(admin to "spec session ended", views.closed)
    }
}
