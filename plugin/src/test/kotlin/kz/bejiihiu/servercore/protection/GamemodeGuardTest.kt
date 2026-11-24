package kz.bejiihiu.servercore.protection

import java.util.UUID
import org.bukkit.GameMode
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GamemodeGuardTest {

    private val uuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private var now = 0L

    /** registry with a hand-moved clock so expiry is deterministic */
    private fun registry(): AllowedGamemodeOverrideRegistry = AllowedGamemodeOverrideRegistry { now }

    // --- registry semantics ---------------------------------------------------

    @Test
    fun `grant sanctions exactly its modes`() {
        val registry = registry()
        registry.grant(uuid, setOf(GameMode.SPECTATOR), "spec")
        assertTrue(registry.isSanctioned(uuid, GameMode.SPECTATOR))
        assertFalse(registry.isSanctioned(uuid, GameMode.CREATIVE))
    }

    @Test
    fun `grant without ttl lasts until revoked`() {
        val registry = registry()
        registry.grant(uuid, setOf(GameMode.SPECTATOR), "spec")
        now = 1_000_000
        assertTrue(registry.isSanctioned(uuid, GameMode.SPECTATOR))
    }

    @Test
    fun `expired grant is not sanctioned`() {
        val registry = registry()
        registry.grant(uuid, setOf(GameMode.SPECTATOR), "spec", ttlMillis = 500)
        assertTrue(registry.isSanctioned(uuid, GameMode.SPECTATOR))
        now = 400
        assertTrue(registry.isSanctioned(uuid, GameMode.SPECTATOR))
        now = 600
        assertFalse(registry.isSanctioned(uuid, GameMode.SPECTATOR))
    }

    @Test
    fun `revoke clears every grant of the player`() {
        val registry = registry()
        registry.grant(uuid, setOf(GameMode.SPECTATOR), "spec")
        registry.grant(uuid, setOf(GameMode.CREATIVE), "event")
        assertTrue(registry.revoke(uuid))
        assertFalse(registry.isSanctioned(uuid, GameMode.SPECTATOR))
        assertFalse(registry.isSanctioned(uuid, GameMode.CREATIVE))
        assertFalse(registry.revoke(uuid))
    }

    @Test
    fun `revoke by source keeps other sources`() {
        val registry = registry()
        registry.grant(uuid, setOf(GameMode.SPECTATOR), "spec")
        registry.grant(uuid, setOf(GameMode.CREATIVE), "event")
        assertTrue(registry.revoke(uuid, "spec"))
        assertFalse(registry.isSanctioned(uuid, GameMode.SPECTATOR))
        assertTrue(registry.isSanctioned(uuid, GameMode.CREATIVE))
    }

    @Test
    fun `sweep drops only expired grants`() {
        val registry = registry()
        val other = UUID.randomUUID()
        registry.grant(uuid, setOf(GameMode.SPECTATOR), "spec", ttlMillis = 100)
        registry.grant(other, setOf(GameMode.CREATIVE), "event")

        now = 200
        assertEquals(1, registry.sweep())
        assertFalse(registry.isSanctioned(uuid, GameMode.SPECTATOR))
        // the other grant had no ttl, it survives the sweep
        assertTrue(registry.isSanctioned(other, GameMode.CREATIVE))
        // second sweep finds nothing expired
        assertEquals(0, registry.sweep())
    }

    @Test
    fun `expiry boundary is exclusive at the deadline`() {
        val registry = registry()
        registry.grant(uuid, setOf(GameMode.CREATIVE), "event", ttlMillis = 100)
        now = 100
        assertFalse(registry.isSanctioned(uuid, GameMode.CREATIVE))
        now = 99
        assertTrue(registry.isSanctioned(uuid, GameMode.CREATIVE))
    }

    // --- guard policy ---------------------------------------------------------

    @Test
    fun `survival is always allowed`() {
        assertTrue(
            GamemodeGuardPolicy.shouldAllowChange(
                GameMode.SURVIVAL,
                PlayerGameModeChangeEvent.Cause.COMMAND,
                sanctioned = false,
            ),
        )
    }

    @Test
    fun `unsanctioned non-survival change is denied`() {
        assertFalse(
            GamemodeGuardPolicy.shouldAllowChange(
                GameMode.CREATIVE,
                PlayerGameModeChangeEvent.Cause.COMMAND,
                sanctioned = false,
            ),
        )
        assertFalse(
            GamemodeGuardPolicy.shouldAllowChange(
                GameMode.SPECTATOR,
                PlayerGameModeChangeEvent.Cause.GAMEMODE_SWITCHER,
                sanctioned = false,
            ),
        )
        assertFalse(
            GamemodeGuardPolicy.shouldAllowChange(
                GameMode.CREATIVE,
                PlayerGameModeChangeEvent.Cause.PLUGIN,
                sanctioned = false,
            ),
        )
    }

    @Test
    fun `a grant sanctions the change`() {
        assertTrue(
            GamemodeGuardPolicy.shouldAllowChange(
                GameMode.SPECTATOR,
                PlayerGameModeChangeEvent.Cause.PLUGIN,
                sanctioned = true,
            ),
        )
    }

    @Test
    fun `vanilla normalization flows are allowed without a grant`() {
        assertTrue(
            GamemodeGuardPolicy.shouldAllowChange(
                GameMode.SURVIVAL,
                PlayerGameModeChangeEvent.Cause.DEFAULT_GAMEMODE,
                sanctioned = false,
            ),
        )
        assertTrue(
            GamemodeGuardPolicy.shouldAllowChange(
                GameMode.SPECTATOR,
                PlayerGameModeChangeEvent.Cause.HARDCORE_DEATH,
                sanctioned = false,
            ),
        )
    }

    @Test
    fun `sweep reverts only unsanctioned non-survival modes`() {
        assertTrue(GamemodeGuardPolicy.shouldRevert(GameMode.CREATIVE, sanctioned = false))
        assertFalse(GamemodeGuardPolicy.shouldRevert(GameMode.SPECTATOR, sanctioned = true))
        assertFalse(GamemodeGuardPolicy.shouldRevert(GameMode.SURVIVAL, sanctioned = false))
    }
}
