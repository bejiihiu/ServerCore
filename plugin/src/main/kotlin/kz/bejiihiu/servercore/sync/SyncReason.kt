package kz.bejiihiu.servercore.sync

/**
 * every state transition the spec's packet-state sync section mandates handling.
 * each one maps onto a [TransitionPolicy] that says what happens on the
 * viewer side (the transitioning player's own client view) and on the target
 * side (how every active viewer of that player must be corrected).
 */
enum class SyncReason {
    /** player (re)joined: client is fresh, registry rows are the source of truth */
    RECONNECT,

    /** moved between worlds: client entity tracking got flushed */
    WORLD_CHANGE,

    /** teleport, including cross-world: full viewer-side reset for the moved target */
    TELEPORT,

    /** player died: dead targets must not render as disguised zombies */
    DEATH,

    /** player respawned: alive again, re-apply the correct policy */
    RESPAWN,

    /** gamemode changed (affects how others see them, e.g. spectator) */
    GAMEMODE_CHANGE,

    /** equipment changed: viewers must get the policy-approved (synthetic) set */
    EQUIPMENT_CHANGE,

    /** the underlying entity changed identity/state underneath a viewer */
    ENTITY_CHANGE,

    /** the entity was removed server-side: stale rows must die with it */
    ENTITY_REMOVAL,

    /** player left: rows are kept on purpose so a reconnect can restore them */
    QUIT,

    /** plugin disable/reload: full restore to vanilla truth */
    PLUGIN_RELOAD,

    /** periodic scheduler-driven reconciliation pass */
    RECONCILE,
}

/** what the viewer side of a transition needs */
enum class ViewerAction {
    /** nothing: the viewer's own view is unaffected by this transition */
    NONE,

    /** drop every applied override on this viewer's client, vanilla resends;
     * used when vanilla tracking is torn down anyway (world change, teleport, respawn) */
    RESET,

    /** re-send the full per-target view from the registry rows without dropping them */
    RESEND,
}

/** what the target side of a transition needs */
enum class TargetAction {
    /** nothing: other viewers' view of this player is unaffected */
    NONE,

    /** re-apply the correct state to every active viewer, from the registry */
    REAPPLY,

    /** cross-world teleport: every viewer of the target gets a full per-target
     * viewer-side reset (overrides dropped, vanilla resend, then re-apply) */
    RESET_FOR_VIEWERS,

    /** death: any DISGUISED pair becomes DEAD via a vanilla restore */
    MARK_DEAD,

    /** entity is gone: drop all cached rows for it so nothing re-attaches */
    DROP,
}

/**
 * the invalidation matrix: which sides of a transition get touched and how.
 * data-driven on purpose, so the matrix test can walk every reason and the
 * policy lives in one readable place instead of being smeared across listeners.
 */
data class TransitionPolicy(
    val viewer: ViewerAction,
    val target: TargetAction,
)

fun policyFor(reason: SyncReason): TransitionPolicy = when (reason) {
    SyncReason.RECONNECT -> TransitionPolicy(ViewerAction.RESEND, TargetAction.REAPPLY)
    SyncReason.WORLD_CHANGE -> TransitionPolicy(ViewerAction.RESET, TargetAction.REAPPLY)
    SyncReason.TELEPORT -> TransitionPolicy(ViewerAction.RESET, TargetAction.RESET_FOR_VIEWERS)
    SyncReason.DEATH -> TransitionPolicy(ViewerAction.NONE, TargetAction.MARK_DEAD)
    SyncReason.RESPAWN -> TransitionPolicy(ViewerAction.RESET, TargetAction.REAPPLY)
    SyncReason.GAMEMODE_CHANGE -> TransitionPolicy(ViewerAction.RESEND, TargetAction.REAPPLY)
    SyncReason.EQUIPMENT_CHANGE -> TransitionPolicy(ViewerAction.NONE, TargetAction.REAPPLY)
    SyncReason.ENTITY_CHANGE -> TransitionPolicy(ViewerAction.NONE, TargetAction.REAPPLY)
    SyncReason.ENTITY_REMOVAL -> TransitionPolicy(ViewerAction.NONE, TargetAction.DROP)
    SyncReason.QUIT -> TransitionPolicy(ViewerAction.NONE, TargetAction.NONE)
    SyncReason.PLUGIN_RELOAD -> TransitionPolicy(ViewerAction.NONE, TargetAction.NONE)
    SyncReason.RECONCILE -> TransitionPolicy(ViewerAction.NONE, TargetAction.NONE)
}
