package kz.bejiihiu.servercore.spec

import java.util.Optional
import java.util.UUID
import kz.bejiihiu.servercore.spectator.SpecSessionReader

/**
 * the session side of the [SpecSessionReader] contract: a reader bound to one
 * admin, answering with the target they currently observe. reads straight from
 * the coordinator registry (concurrent map under a lock), so it is safe from
 * any thread and never blocks.
 */
class CoordinatorSessionReader(
    private val coordinator: SessionCoordinator,
    private val adminId: UUID,
) : SpecSessionReader {

    override fun currentTarget(): Optional<UUID> =
        Optional.ofNullable(coordinator.activeSession(adminId)?.targetId)
}
