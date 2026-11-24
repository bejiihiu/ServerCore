package kz.bejiihiu.servercore.esp

import java.util.Random

/** everything the decider is allowed to know about one (viewer, target) pair */
data class PairFacts(
    val sameWorld: Boolean,
    val distanceSquared: Double,
    /** true = geometry blocks every probe ray, false = clear, null = still in flight */
    val losBlocked: Boolean?,
    val targetSneaking: Boolean,
    val viewerSpectator: Boolean,
    val targetSpectator: Boolean,
    val viewerDead: Boolean,
    val targetDead: Boolean,
    val bothGuests: Boolean,
    /** target is an admin running /spec observation */
    val targetSpecObserving: Boolean,
)

/** frozen config slice the pure core works against */
data class EspRules(
    val maxDistanceSquared: Double,
    val sneakHides: Boolean,
    val disguiseEnabled: Boolean,
    val mobs: List<String>,
) {
    companion object {
        fun defaults() = EspRules(
            maxDistanceSquared = 48.0 * 48.0,
            sneakHides = true,
            disguiseEnabled = true,
            mobs = EspConfig.defaultMobs,
        )
    }
}

/** verdict of the decision core: what a viewer should see of a target */
data class PairVerdict(val state: ViewState, val disguise: DisguiseAssignment? = null)

/**
 * pure, bukkit-free decision core. every rule about who sees whom as what
 * lives here so the whole thing is unit-testable without a server or packet
 * events anywhere near the classpath.
 */
class PairVisibilityDecider(private val rules: EspRules) {

    /**
     * true when the verdict cannot be computed without block geometry. cheap
     * gates (exclusions, distance, sneak) short-circuit before any raytrace.
     */
    fun needsLineOfSight(facts: PairFacts): Boolean {
        if (facts.losBlocked != null) return false
        if (!facts.sameWorld) return false
        if (facts.viewerSpectator || facts.viewerDead || facts.targetDead || facts.targetSpectator) return false
        if (facts.bothGuests || facts.targetSpecObserving) return false
        if (facts.distanceSquared > rules.maxDistanceSquared) return false
        if (rules.sneakHides && facts.targetSneaking) return false
        return true
    }

    /**
     * full decision. [facts.losBlocked] may be null only when the caller keeps
     * the previous verdict while a trace is in flight; [previous] carries the
     * last applied verdict so pending pairs don't flicker.
     */
    fun decide(facts: PairFacts, previous: PairVerdict?, rng: Random): PairVerdict {
        // pairs we don't manage at all fall back to vanilla visibility:
        // spectator viewers see everything anyway, death and spectators are
        // vanilla business, guests see guests normally (product decision).
        // same-team via luckperms prefix is intentionally out of scope here,
        // documented as a known limitation.
        if (!facts.sameWorld || facts.viewerSpectator || facts.viewerDead ||
            facts.targetDead || facts.targetSpectator || facts.bothGuests
        ) {
            return PairVerdict(ViewState.VISIBLE)
        }

        // an admin under /spec is hidden from the observed player outright,
        // the spec module consumes this state
        if (facts.targetSpecObserving) return PairVerdict(ViewState.HIDDEN)

        // outside the managed radius we stop pretending: vanilla already
        // stops tracking far entities on its own
        if (facts.distanceSquared > rules.maxDistanceSquared) return PairVerdict(ViewState.VISIBLE)

        // sneaking targets vanish for every viewer, no geometry needed and
        // no raytrace billed for it. yes this hides them from viewers standing
        // right there too - that is what the product spec asks for
        if (rules.sneakHides && facts.targetSneaking) return PairVerdict(ViewState.HIDDEN)

        val blocked = facts.losBlocked ?: return pendingFallback(previous)
        return if (!blocked) {
            PairVerdict(ViewState.VISIBLE)
        } else if (rules.disguiseEnabled) {
            // stay on the same mob for as long as the pair stays disguised,
            // re-rolling every refresh would flicker models on the client
            val disguise = if (previous?.state == ViewState.DISGUISED && previous.disguise != null) {
                previous.disguise
            } else {
                DisguiseAssignment(pickMob(rng))
            }
            PairVerdict(ViewState.DISGUISED, disguise)
        } else {
            PairVerdict(ViewState.HIDDEN)
        }
    }

    /** no line-of-sight answer yet: keep whatever was applied, else fail closed */
    private fun pendingFallback(previous: PairVerdict?): PairVerdict =
        previous ?: PairVerdict(ViewState.HIDDEN)

    private fun pickMob(rng: Random): String {
        val mobs = rules.mobs
        // config load guarantees a non-empty list, belt and braces anyway
        if (mobs.isEmpty()) return EspConfig.defaultMobs.first()
        return mobs[rng.nextInt(mobs.size)]
    }
}
