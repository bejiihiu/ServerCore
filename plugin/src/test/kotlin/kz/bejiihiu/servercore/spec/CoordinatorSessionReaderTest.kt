package kz.bejiihiu.servercore.spec

import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoordinatorSessionReaderTest {

    @Test
    fun `reader answers with the bound session target and empties after exit`() {
        val coordinator = SessionCoordinator(RecordingEffects(), null)
        val admin = UUID.randomUUID()
        val steve = ObservationTarget(UUID.randomUUID(), "steve", levelIndex = null)

        val reader = CoordinatorSessionReader(coordinator, admin)
        assertTrue(reader.currentTarget().isEmpty)

        coordinator.start(admin, "admin", steve)
        assertEquals(Optional.of(steve.uuid), reader.currentTarget())

        coordinator.exit(admin, ExitReason.COMMAND)
        assertTrue(reader.currentTarget().isEmpty)
    }
}
