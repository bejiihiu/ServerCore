package kz.bejiihiu.servercore.sync

import java.util.UUID

/**
 * the per-(viewer, target) client view state, as the spec's personal entity
 * representation section defines it: two clients are allowed to see the same
 * server entity completely differently, so visibility and disguise state is
 * stored per pair, never globally.
 *
 * DEAD is our addition on top of the three canonical states: a dead target
 * must never keep rendering as a disguised zombie to its viewers, so death
 * pins the pair into DEAD until respawn re-applies the real policy.
 */
enum class ViewState {
    /** vanilla representation, no override applied on this pair */
    VISIBLE,

    /** client sees the synthetic replacement entity, not the real one */
    DISGUISED,

    /** client receives no representation of the entity at all */
    HIDDEN,

    /** target is dead: disguise forcibly removed, vanilla death is shown */
    DEAD,
}

/** one cached (viewer -> target) view state row */
data class ViewStateRow(
    val viewer: UUID,
    val target: UUID,
    val state: ViewState,
)
