package kz.bejiihiu.servercore.spectator

import java.util.UUID

/** play-server packet families the trace filter looks at */
enum class TraceKind { PARTICLE, SOUND, ACTION_BAR }

enum class TraceDecision { DROP, KEEP }

/** plain xyz, kept free of packetevents types so the whole filter is testable */
data class TracePos(val x: Double, val y: Double, val z: Double) {
    fun distanceSquaredTo(other: TracePos): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }
}

/**
 * pure decision core for trace suppression: which outbound packets toward an
 * observed player look like they were caused by the observing admin.
 *
 * the heuristic, honestly stated: a spectator admin cannot physically cause
 * particles or sounds, so a positioned packet landing within [radius] of the
 * admin's captured entity position is treated as admin-origin. that catches
 * the realistic leaks (admin-driven plugin effects around their camera) and
 * carries one known false positive: a spectator admin following their target
 * sits within arm's reach of them, so effects the target causes at their own
 * feet can be dropped as well. the radius is the tuning knob for that trade.
 *
 * what cannot be attributed at packet level and passes through untouched:
 * - unpositioned sound packets (ui category sounds carry a position of 0/0/0,
 *   which is treated as unattributable and kept)
 * - global chat broadcasts
 * third-party plugins that announce admin actions to every player will still
 * be seen by the target; suppressing those would blind the target to real
 * server feedback, which is worse than the leak.
 *
 * fail direction: with no admin position captured yet, or no position on the
 * packet, the decision is always KEEP — never blind the target on a maybe.
 */
class TraceFilter(
    private val radius: Double,
    private val suppressActionBar: Boolean,
) {

    fun decide(kind: TraceKind, adminPos: TracePos?, packetPos: TracePos?): TraceDecision {
        if (adminPos == null) return TraceDecision.KEEP
        return when (kind) {
            TraceKind.ACTION_BAR ->
                // actionbar text carries no position, attribution is
                // impossible: within an active session the whole channel is
                // dropped when configured, everything else keeps flowing
                if (suppressActionBar) TraceDecision.DROP else TraceDecision.KEEP

            TraceKind.PARTICLE, TraceKind.SOUND -> {
                // a sound/particle at the world origin is a UI-ish play, not
                // something caused at the admin's feet — never attribute it
                if (packetPos == null || isZero(packetPos)) return TraceDecision.KEEP
                if (packetPos.distanceSquaredTo(adminPos) <= radius * radius) {
                    TraceDecision.DROP
                } else {
                    TraceDecision.KEEP
                }
            }
        }
    }

    private fun isZero(pos: TracePos): Boolean = pos.x == 0.0 && pos.y == 0.0 && pos.z == 0.0
}

/**
 * pure scoping for entity packets about a hidden admin: a packet concerning
 * [owner] sent toward [viewer] is in scope when the owner is an admin
 * currently observing that very viewer.
 */
object ObserverScope {
    fun isHiddenPair(owner: UUID?, viewer: UUID, sessions: SpecSessionSource): Boolean =
        owner != null && owner != viewer && sessions.isObserving(owner, viewer)
}

/**
 * deterministic restore ordering for shutdown, same policy as the sync
 * module: sorted by admin, then target, so a reload can never depend on map
 * iteration luck.
 */
object RestorePlan {
    fun ordered(pairs: Set<Pair<UUID, UUID>>): List<Pair<UUID, UUID>> =
        pairs.sortedWith(compareBy({ it.first }, { it.second }))
}
