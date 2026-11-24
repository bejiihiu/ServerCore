package kz.bejiihiu.servercore.esp

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairVisibilityDeciderTest {

    private val rules = EspRules(
        maxDistanceSquared = 48.0 * 48.0,
        sneakHides = true,
        disguiseEnabled = true,
        mobs = listOf("ZOMBIE", "SKELETON", "SPIDER"),
    )

    private val decider = PairVisibilityDecider(rules)

    private fun facts(
        distance: Double = 10.0,
        losBlocked: Boolean? = false,
        sneaking: Boolean = false,
        viewerSpectator: Boolean = false,
        targetSpectator: Boolean = false,
        viewerDead: Boolean = false,
        targetDead: Boolean = false,
        bothGuests: Boolean = false,
        targetSpec: Boolean = false,
        sameWorld: Boolean = true,
    ) = PairFacts(
        sameWorld = sameWorld,
        distanceSquared = distance * distance,
        losBlocked = losBlocked,
        targetSneaking = sneaking,
        viewerSpectator = viewerSpectator,
        targetSpectator = targetSpectator,
        viewerDead = viewerDead,
        targetDead = targetDead,
        bothGuests = bothGuests,
        targetSpecObserving = targetSpec,
    )

    @Test
    fun clearSightIsVisible() {
        val verdict = decider.decide(facts(distance = 30.0, losBlocked = false), null, Random(1))
        assertEquals(PairVerdict(ViewState.VISIBLE), verdict)
    }

    @Test
    fun beyondMaxDistanceFallsBackToVanilla() {
        // exactly at the boundary is still managed (a blocked sight disguises),
        // one block further the engine lets go even with no sight answer yet
        assertEquals(
            ViewState.DISGUISED,
            decider.decide(facts(distance = 48.0, losBlocked = true), null, Random(1)).state,
        )
        assertEquals(
            PairVerdict(ViewState.VISIBLE),
            decider.decide(facts(distance = 48.1, losBlocked = null), null, Random(1)),
        )
    }

    @Test
    fun blockedSightDisguisesWithMobFromList() {
        val verdict = decider.decide(facts(distance = 30.0, losBlocked = true), null, Random(7))
        assertEquals(ViewState.DISGUISED, verdict.state)
        assertTrue(verdict.disguise!!.entityType in rules.mobs)
    }

    @Test
    fun blockedSightWithoutDisguiseHides() {
        val noDisguise = EspRules(48.0 * 48.0, sneakHides = true, disguiseEnabled = false, mobs = rules.mobs)
        val verdict = PairVisibilityDecider(noDisguise).decide(facts(losBlocked = true), null, Random(1))
        assertEquals(PairVerdict(ViewState.HIDDEN), verdict)
    }

    @Test
    fun disguiseStaysStableWhileContinuouslyDisguised() {
        val rng = Random(42)
        val first = decider.decide(facts(losBlocked = true), null, rng)
        val second = decider.decide(facts(losBlocked = true), first, rng)
        assertEquals(first.disguise, second.disguise)
    }

    @Test
    fun disguiseReRollsAfterLeavingDisguisedState() {
        val rng = Random(42)
        val first = decider.decide(facts(losBlocked = true), null, rng)
        // condition clears then blocks again -> new roll, and the rng stream
        // must be identical for identical seeds (determinism)
        val rerolled = decider.decide(facts(losBlocked = true), PairVerdict(ViewState.VISIBLE), rng)
        assertFalse(first.disguise == null)
        // same seed from scratch produces the same first pick
        val replay = decider.decide(facts(losBlocked = true), null, Random(42))
        assertEquals(first, replay)
        assertEquals(ViewState.DISGUISED, rerolled.state)
    }

    @Test
    fun sneakingHidesEvenBeforeGeometryAnswers() {
        // losBlocked = null (trace in flight) must still hide instantly
        val verdict = decider.decide(facts(sneaking = true, losBlocked = null), null, Random(1))
        assertEquals(PairVerdict(ViewState.HIDDEN), verdict)
        assertFalse(decider.needsLineOfSight(facts(sneaking = true, losBlocked = null)))
    }

    @Test
    fun sneakHidesOffLetsSneakersStayManagedByGeometry() {
        val lax = EspRules(48.0 * 48.0, sneakHides = false, disguiseEnabled = true, mobs = rules.mobs)
        val d = PairVisibilityDecider(lax)
        assertTrue(d.needsLineOfSight(facts(sneaking = true, losBlocked = null)))
        assertEquals(PairVerdict(ViewState.VISIBLE), d.decide(facts(sneaking = true, losBlocked = false), null, Random(1)))
    }

    @Test
    fun pendingSightFailsClosedOnFirstCheck() {
        val verdict = decider.decide(facts(losBlocked = null), null, Random(1))
        assertEquals(PairVerdict(ViewState.HIDDEN), verdict)
    }

    @Test
    fun pendingSightKeepsPreviousVerdict() {
        val previous = PairVerdict(ViewState.VISIBLE)
        val verdict = decider.decide(facts(losBlocked = null), previous, Random(1))
        assertEquals(previous, verdict)
    }

    @Test
    fun exclusionsFallBackToVanillaVisibility() {
        val visible = PairVerdict(ViewState.VISIBLE)
        assertEquals(visible, decider.decide(facts(viewerSpectator = true, losBlocked = true), null, Random(1)))
        assertEquals(visible, decider.decide(facts(targetSpectator = true, losBlocked = true), null, Random(1)))
        assertEquals(visible, decider.decide(facts(viewerDead = true, losBlocked = true), null, Random(1)))
        assertEquals(visible, decider.decide(facts(targetDead = true, losBlocked = true), null, Random(1)))
        assertEquals(visible, decider.decide(facts(bothGuests = true, losBlocked = true), null, Random(1)))
        assertEquals(visible, decider.decide(facts(sameWorld = false, losBlocked = true), null, Random(1)))
    }

    @Test
    fun specObservingTargetIsHiddenFromEveryone() {
        val verdict = decider.decide(facts(targetSpec = true, losBlocked = false), null, Random(1))
        assertEquals(PairVerdict(ViewState.HIDDEN), verdict)
        assertFalse(decider.needsLineOfSight(facts(targetSpec = true, losBlocked = null)))
    }

    @Test
    fun needsLineOfSightMirrorsTheCheapGates() {
        assertTrue(decider.needsLineOfSight(facts(losBlocked = null)))
        assertFalse(decider.needsLineOfSight(facts(distance = 100.0, losBlocked = null)))
        assertFalse(decider.needsLineOfSight(facts(losBlocked = false)))
        assertFalse(decider.needsLineOfSight(facts(viewerSpectator = true, losBlocked = null)))
    }

    @Test
    fun guestViewerAgainstAuthorizedTargetStillGetsManaged() {
        // only the both-guests case is exempt from the engine
        val verdict = decider.decide(facts(bothGuests = false, losBlocked = true), null, Random(1))
        assertEquals(ViewState.DISGUISED, verdict.state)
    }
}
