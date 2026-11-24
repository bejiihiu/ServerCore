package kz.bejiihiu.servercore.packet

/**
 * what a specific viewer's client knows (or is allowed to know) about a target
 * entity. per-viewer on purpose: two clients looking at the same server entity
 * may see completely different things.
 */
enum class EntityViewState {
    /** the client receives the real representation */
    VISIBLE,

    /** the client receives a synthetic representation, details owned by the disguise layer */
    DISGUISED,

    /** the client receives no representation at all */
    HIDDEN,
}
