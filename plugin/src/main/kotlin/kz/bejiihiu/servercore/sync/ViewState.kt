package kz.bejiihiu.servercore.sync

import java.util.UUID
import kz.bejiihiu.servercore.esp.ViewState as CanonicalViewState

/**
 * the per-(viewer, target) client view state, as the spec's personal entity
 * representation section defines it: two clients are allowed to see the same
 * server entity completely differently, so visibility and disguise state is
 * stored per pair, never globally.
 *
 * the enum itself is the one canonical definition in the esp module; this
 * alias keeps the sync module's naming. DEAD — the sync layer's own addition
 * on top of the three engine states — lives in the canonical enum: a dead
 * target must never keep rendering as a disguised zombie to its viewers, so
 * death pins the pair into DEAD until respawn re-applies the real policy.
 */
typealias ViewState = CanonicalViewState

/** one cached (viewer -> target) view state row */
data class ViewStateRow(
    val viewer: UUID,
    val target: UUID,
    val state: ViewState,
)
