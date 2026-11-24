package kz.bejiihiu.servercore.esp

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * guest interplay with the engine exclusions, per the wiring contract: the
 * engine flags guest sessions in as `bothGuests`, and the only guest exemption
 * is the guest-viewing-guest pair. every other exclusion — spectator, dead,
 * sneak, spec observation — must hold exactly the same when one side is a
 * guest, because a guest watching a regular player is managed anti-esp
 * business like anyone else.
 */
class GuestInterplayTest {

    private val decider = PairVisibilityDecider(EspRules.defaults())

    private fun facts(
        bothGuests: Boolean,
        sneaking: Boolean = false,
        viewerSpectator: Boolean = false,
        targetSpectator: Boolean = false,
        viewerDead: Boolean = false,
        targetDead: Boolean = false,
        losBlocked: Boolean? = true,
    ) = PairFacts(
        sameWorld = true,
        distanceSquared = 10.0 * 10.0,
        losBlocked = losBlocked,
        targetSneaking = sneaking,
        viewerSpectator = viewerSpectator,
        targetSpectator = targetSpectator,
        viewerDead = viewerDead,
        targetDead = targetDead,
        bothGuests = bothGuests,
        targetSpecObserving = false,
    )

    @Test
    fun guestViewerAgainstGuestTargetIsVanillaEvenBehindWalls() {
        // the product exemption: guests see guests normally, no disguise,
        // no hiding, no geometry billed
        assertEquals(
            PairVerdict(ViewState.VISIBLE),
            decider.decide(facts(bothGuests = true, losBlocked = true), null, Random(1)),
        )
        assertFalse(decider.needsLineOfSight(facts(bothGuests = true, losBlocked = null)))
    }

    @Test
    fun guestPairExemptionBeatsSneakingAndSpectatorTargets() {
        val visible = PairVerdict(ViewState.VISIBLE)
        assertEquals(visible, decider.decide(facts(bothGuests = true, sneaking = true), null, Random(1)))
        assertEquals(visible, decider.decide(facts(bothGuests = true, targetSpectator = true), null, Random(1)))
    }

    @Test
    fun deadTargetStaysExcludedWhenGuestsAreInvolved() {
        // dead target: vanilla death, never a disguise — guest or not
        assertEquals(
            PairVerdict(ViewState.VISIBLE),
            decider.decide(facts(bothGuests = true, targetDead = true), null, Random(1)),
        )
        assertEquals(
            PairVerdict(ViewState.VISIBLE),
            decider.decide(facts(bothGuests = false, targetDead = true), null, Random(1)),
        )
    }

    @Test
    fun spectatorViewerStaysExcludedWhenGuestsAreInvolved() {
        assertEquals(
            PairVerdict(ViewState.VISIBLE),
            decider.decide(facts(bothGuests = true, viewerSpectator = true), null, Random(1)),
        )
        // a guest in spectator watching a regular player: spectator exclusion wins
        assertEquals(
            PairVerdict(ViewState.VISIBLE),
            decider.decide(facts(bothGuests = false, viewerSpectator = true), null, Random(1)),
        )
    }

    @Test
    fun guestViewerStillGetsTheFullAntiEspTreatment() {
        // one guest, one regular: managed exactly like a regular pair
        assertEquals(
            ViewState.DISGUISED,
            decider.decide(facts(bothGuests = false), null, Random(1)).state,
        )
    }

    @Test
    fun guestTargetSneakingIsHiddenForRegularViewers() {
        // the regular player is the one being protected from esp here: a
        // sneaking guest disappears for them like any other sneaking target
        assertEquals(
            PairVerdict(ViewState.HIDDEN),
            decider.decide(facts(bothGuests = false, sneaking = true, losBlocked = null), null, Random(1)),
        )
    }

    @Test
    fun viewerDeathExcludesThePairRegardlessOfGuestStatus() {
        // a dead viewer is on the respawn screen: vanilla business in both
        // directions, no engine verdict may touch the pair
        assertEquals(
            PairVerdict(ViewState.VISIBLE),
            decider.decide(facts(bothGuests = true, viewerDead = true), null, Random(1)),
        )
        assertEquals(
            PairVerdict(ViewState.VISIBLE),
            decider.decide(facts(bothGuests = false, viewerDead = true), null, Random(1)),
        )
    }
}
