package kz.bejiihiu.servercore.spectator

import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * contract check for the session-read seam: the viewer relies on these exact
 * semantics, the session side guarantees them. the fake here is also the shape
 * the session implementation is expected to follow — an Optional-returning,
 * non-blocking, thread-safe read.
 */
class SpecSessionReaderContractTest {

    /** minimal in-memory reader, mirrors the documented contract */
    private class FakeSessionReader(private var target: UUID?) : SpecSessionReader {
        @Volatile
        private var current = target

        fun startSession(target: UUID) {
            current = target
        }

        fun endSession() {
            current = null
        }

        override fun currentTarget(): Optional<UUID> = Optional.ofNullable(current)
    }

    @Test
    fun `no active session reads as empty`() {
        val reader = FakeSessionReader(null)
        assertEquals(Optional.empty<UUID>(), reader.currentTarget())
        assertFalse(reader.currentTarget().isPresent)
    }

    @Test
    fun `active session exposes its target uuid`() {
        val target = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val reader = FakeSessionReader(target)
        assertTrue(reader.currentTarget().isPresent)
        assertEquals(target, reader.currentTarget().get())
    }

    @Test
    fun `session lifecycle transitions are reflected immediately`() {
        val reader = FakeSessionReader(null)
        val target = UUID.randomUUID()
        reader.startSession(target)
        assertEquals(Optional.of(target), reader.currentTarget())
        reader.endSession()
        assertEquals(Optional.empty<UUID>(), reader.currentTarget())
    }

    @Test
    fun `independent readers do not leak state into each other`() {
        val watching = FakeSessionReader(UUID.randomUUID())
        val idle = FakeSessionReader(null)
        assertTrue(watching.currentTarget().isPresent)
        assertFalse(idle.currentTarget().isPresent)
    }
}
