package kz.bejiihiu.servercore.bars

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * bars.json durability: a toggle made in a session is the same bar after a
 * relog (the whole point over purpur), and a hand-edited or stale file never
 * fails the boot.
 */
class BarsStoreTest {

    @TempDir
    lateinit var dir: Path

    private val alice: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun store(name: String = "bars.json") = BarsStore(dir.resolve(name), Runnable::run)

    @Test
    fun `a written choice survives a reload`() {
        val state = BarsState()
        state.enable(alice, BarId.TPS, BarSurface.BOSSBAR)
        store().writeNow(state.snapshot())

        val loaded = store().load()

        assertEquals(BarId.TPS, loaded[alice]?.get(BarSurface.BOSSBAR))
    }

    @Test
    fun `an absent file boots off with nobody subscribed`() {
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `unknown uuids, surfaces and bar keys are dropped instead of crashing`() {
        Files.writeString(
            dir.resolve("bars.json"),
            """
            {"players":{
              "not-a-uuid":{"bossbar":"ram"},
              "00000000-0000-0000-0000-000000000001":{"scoreboard":"ram","bossbar":"ghost"},
              "00000000-0000-0000-0000-000000000002":{"bossbar":"tps"}
            }}
            """.trimIndent(),
        )

        val loaded = store().load()

        assertEquals(1, loaded.size)
        assertEquals(
            BarId.TPS,
            loaded[UUID.fromString("00000000-0000-0000-0000-000000000002")]?.get(BarSurface.BOSSBAR),
        )
    }

    @Test
    fun `a corrupt file is quarantined and the store starts clean`() {
        Files.writeString(dir.resolve("bars.json"), "not json at all {{{")

        assertTrue(store().load().isEmpty())
        assertTrue(Files.notExists(dir.resolve("bars.json")))
    }
}
