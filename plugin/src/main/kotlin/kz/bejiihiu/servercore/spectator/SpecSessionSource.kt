package kz.bejiihiu.servercore.spectator

import java.util.UUID

/**
 * read-only seam the packet layer uses to ask "is this admin watching that
 * player right now". the session coordinator owns the answer and the hiding
 * bridge mirrors its settled state into [ObserverPairRegistry], so the packet
 * layer never talks to the coordinator directly and never decides conflicts
 * on its own.
 */
fun interface SpecSessionSource {
    /** true while [admin] is running an active observation of [target] */
    fun isObserving(admin: UUID, target: UUID): Boolean
}
