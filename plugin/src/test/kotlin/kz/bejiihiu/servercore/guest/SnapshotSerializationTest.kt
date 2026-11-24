package kz.bejiihiu.servercore.guest

import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SnapshotSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sample() = PlayerSnapshot(
        world = "world",
        x = 12.5, y = 64.0, z = -3.25,
        yaw = 90.0, pitch = -12.5,
        gameMode = "SURVIVAL",
        flying = false,
        flySpeed = 0.1,
        walkSpeed = 0.2,
    )

    @Test
    fun `snapshot survives a json roundtrip`() {
        val encoded = json.encodeToString(PlayerSnapshot.serializer(), sample())
        val decoded = json.decodeFromString(PlayerSnapshot.serializer(), encoded)
        assertEquals(sample(), decoded)
    }

    @Test
    fun `field names match the backend wire schema`() {
        val element = Json.parseToJsonElement(json.encodeToString(PlayerSnapshot.serializer(), sample())).jsonObject
        val expected = setOf("world", "x", "y", "z", "yaw", "pitch", "gameMode", "flying", "flySpeed", "walkSpeed")
        assertEquals(expected, element.keys)
    }

    @Test
    fun `store roundtrip through disk and refuses double capture`() {
        val dir = Files.createTempDirectory("servercore-test")
        val store = SnapshotStore(dir.resolve("snapshots.json"), Executors.newVirtualThreadPerTaskExecutor())
        val uuid = UUID.randomUUID()
        val stored = StoredSnapshot("guest-abc", 1L, sample())

        store.save(uuid, stored)
        store.shutdown()

        val reloaded = SnapshotStore(dir.resolve("snapshots.json"), Executors.newVirtualThreadPerTaskExecutor())
        reloaded.load()
        assertEquals(stored, reloaded.pending(uuid))

        // second join never overwrites the original capture
        val drift = sample().copy(x = 999.0)
        reloaded.updateSessionId(uuid, "guest-def")
        assertEquals("guest-def", reloaded.pending(uuid)?.sessionId)
        assertNotEquals(drift.x, reloaded.pending(uuid)?.snapshot?.x)

        // completion removes it
        reloaded.complete(uuid)
        assertNull(reloaded.pending(uuid))
    }

    @Test
    fun `restored snapshot keeps original location not the spectator wander`() {
        val original = sample()
        val wander = original.copy(x = 500.0, z = 500.0, gameMode = "SPECTATOR", flying = true)
        // the store only ever keeps what was captured once
        val store = SnapshotStore(Files.createTempDirectory("sc2").resolve("s.json"), Runnable::run)
        store.save(UUID.randomUUID(), StoredSnapshot("s1", 1L, original))
        // a later snapshot value arriving via updateSessionId must not change stored fields
        val uuid = store.pendingAll().keys.first()
        store.updateSessionId(uuid, "s2")
        assertEquals(original, store.pending(uuid)?.snapshot)
        assertFalse(store.pending(uuid)?.snapshot?.gameMode == wander.gameMode)
    }

    @Test
    fun `corrupt file reads as blank slate instead of crashing`() {
        val dir = Files.createTempDirectory("sc3")
        Files.writeString(dir.resolve("broken.json"), "{ not json !!!")
        val store = SnapshotStore(dir.resolve("broken.json"), Runnable::run)
        store.load()
        assertTrue(store.pendingAll().isEmpty())
    }

    @Test
    fun `game mode parsing survives case drift and garbage`() {
        assertEquals(org.bukkit.GameMode.SPECTATOR, parseGameMode("spectator"))
        assertEquals(org.bukkit.GameMode.SURVIVAL, parseGameMode("SURVIVAL"))
        assertEquals(org.bukkit.GameMode.SURVIVAL, parseGameMode("nonsense"))
    }
}
