package kz.bejiihiu.servercore.spectator

import java.util.UUID

/**
 * the seam the /spec session side plugs into. the command module owns session
 * lifecycle (start, permission ordering, audit); this module owns the packet
 * layer hiding. both sides agree on this single method, so the two branches
 * merge mechanically: whoever holds the live registry binds it in here.
 */
fun interface SpecSessionSource {
    /** true while [admin] is running an active observation of [target] */
    fun isObserving(admin: UUID, target: UUID): Boolean
}
