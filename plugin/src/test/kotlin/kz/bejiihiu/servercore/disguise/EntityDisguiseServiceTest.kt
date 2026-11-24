package kz.bejiihiu.servercore.disguise

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntityDisguiseServiceTest {

    private val viewer = UUID.nameUUIDFromBytes("viewer".toByteArray())
    private val target = UUID.nameUUIDFromBytes("target".toByteArray())
    private val mobs = listOf("zombie", "cow", "pig")

    /** records renderer calls as readable strings, no packetevents involved */
    private class FakeRenderer : DisguiseRenderer {
        val calls = mutableListOf<String>()

        override fun spawnDisguise(
            viewer: UUID,
            target: UUID,
            realEntityId: Int,
            syntheticEntityId: Int,
            profile: SyntheticMobProfile,
            snapshot: DisguiseSnapshot,
        ) {
            calls += "spawn:$viewer/$target/real=$realEntityId/syn=$syntheticEntityId/mob=${profile.mobTypeName}"
        }

        override fun destroyEntities(viewer: UUID, entityIds: IntArray) {
            calls += "destroy:$viewer/${entityIds.toList()}"
        }

        override fun revealPlayer(viewer: UUID, target: UUID, realEntityId: Int, snapshot: DisguiseSnapshot) {
            calls += "reveal:$viewer/$target/real=$realEntityId"
        }
    }

    /** inline "scheduler": the hop runs immediately with canned entity data */
    private class InlineScheduler(
        private val entityIds: MutableMap<UUID, Int> = mutableMapOf(),
    ) : DisguiseScheduler {
        override fun run(target: UUID, block: (TargetEntity) -> Unit) {
            block(TargetEntity(entityIds[target] ?: 900, DisguiseSnapshot(1.0, 2.0, 3.0, 0f, 0f)))
        }
    }

    private fun service(
        renderer: FakeRenderer,
        scheduler: InlineScheduler = InlineScheduler(),
        registry: DisguiseRegistry = DisguiseRegistry(),
    ): EntityDisguiseService = EntityDisguiseService(
        scheduler, renderer, registry, SyntheticEntityIds(), mobs,
    )

    @Test
    fun repeatedDisguiseApplyIsIdempotent() {
        val renderer = FakeRenderer()
        val svc = service(renderer)

        svc.apply(viewer, target, EntityViewState.DISGUISED)
        svc.apply(viewer, target, EntityViewState.DISGUISED)
        svc.apply(viewer, target, EntityViewState.DISGUISED)

        assertEquals(1, renderer.calls.count { it.startsWith("spawn:") }, renderer.calls.toString())
        // the real-player destroy rides INSIDE spawnDisguise in the real
        // renderer, so the fake sees zero separate destroy calls here
        assertEquals(0, renderer.calls.count { it.startsWith("destroy:") }, renderer.calls.toString())
    }

    @Test
    fun revealWithoutAnyPriorStateDoesNothing() {
        val renderer = FakeRenderer()
        service(renderer).apply(viewer, target, EntityViewState.VISIBLE)
        assertTrue(renderer.calls.isEmpty(), renderer.calls.toString())
    }

    @Test
    fun disguiseThenRevealRunsExactlyOnceAndIsIdempotent() {
        val renderer = FakeRenderer()
        val svc = service(renderer)

        svc.apply(viewer, target, EntityViewState.DISGUISED)
        svc.apply(viewer, target, EntityViewState.VISIBLE)
        svc.apply(viewer, target, EntityViewState.VISIBLE)

        assertEquals(1, renderer.calls.count { it.startsWith("spawn:") })
        assertEquals(1, renderer.calls.count { it.startsWith("reveal:") })
        // synthetic destroyed exactly once, before the reveal
        val syntheticId = renderer.calls.first { it.startsWith("spawn:") }
            .substringAfter("syn=").substringBefore('/').toInt()
        assertEquals("destroy:$viewer/[$syntheticId]", renderer.calls.first { it.startsWith("destroy:") })
        assertTrue(renderer.calls.indexOf(renderer.calls.first { it.startsWith("destroy:") }) <
            renderer.calls.indexOf(renderer.calls.first { it.startsWith("reveal:") }),
            "reveal must come after the synthetic destroy (no flicker)")
    }

    @Test
    fun disguiseThenHideDestroysSyntheticAndReal() {
        val renderer = FakeRenderer()
        val scheduler = InlineScheduler(entityIds = mutableMapOf(target to 777))
        val svc = service(renderer, scheduler)

        svc.apply(viewer, target, EntityViewState.DISGUISED)
        svc.apply(viewer, target, EntityViewState.HIDDEN)

        val destroy = renderer.calls.last { it.startsWith("destroy:") }
        // synthetic id + the real entity id 777, nothing else
        assertTrue(destroy.endsWith(", 777]"), destroy)
    }

    @Test
    fun hideWithoutPriorStateStillParksARecordAndDestroysReal() {
        val renderer = FakeRenderer()
        val scheduler = InlineScheduler(entityIds = mutableMapOf(target to 555))
        val svc = service(renderer, scheduler)

        svc.apply(viewer, target, EntityViewState.HIDDEN)
        assertEquals(listOf("destroy:$viewer/[555]"), renderer.calls)

        // a later reveal restores the parked entity exactly once
        svc.apply(viewer, target, EntityViewState.VISIBLE)
        assertEquals(listOf("destroy:$viewer/[555]", "reveal:$viewer/$target/real=555"), renderer.calls)
        svc.apply(viewer, target, EntityViewState.VISIBLE)
        assertEquals(2, renderer.calls.size, "second reveal must be a no-op")
    }

    @Test
    fun mobChoiceSurvivesAHiddentoDisguisedRoundTrip() {
        val renderer = FakeRenderer()
        val svc = service(renderer)

        svc.apply(viewer, target, EntityViewState.DISGUISED)
        val first = renderer.calls.first { it.startsWith("spawn:") }

        svc.apply(viewer, target, EntityViewState.HIDDEN)
        svc.apply(viewer, target, EntityViewState.DISGUISED)
        val second = renderer.calls.last { it.startsWith("spawn:") }

        // seeded per pair: the resync respawns the same model, not a new one
        assertEquals(first.substringAfter("mob="), second.substringAfter("mob="))
        // and the synthetic id is stable within the pair's lifetime
        assertEquals(first.substringAfter("syn=").substringBefore('/'), second.substringAfter("syn=").substringBefore('/'))
    }

    @Test
    fun viewerNeverDisguisesItself() {
        val renderer = FakeRenderer()
        service(renderer).apply(viewer, viewer, EntityViewState.DISGUISED)
        assertTrue(renderer.calls.isEmpty())
    }

    @Test
    fun clearForTargetWipesEveryViewersGhost() {
        val renderer = FakeRenderer()
        val registry = DisguiseRegistry()
        val other = UUID.nameUUIDFromBytes("other-viewer".toByteArray())
        val svc = service(renderer, registry = registry)

        svc.apply(viewer, target, EntityViewState.DISGUISED)
        svc.apply(other, target, EntityViewState.DISGUISED)
        renderer.calls.clear()

        svc.clearFor(target)

        // both viewers lose their synthetic ghost, records gone -> reveal no-ops
        assertEquals(2, renderer.calls.count { it.startsWith("destroy:") })
        svc.apply(viewer, target, EntityViewState.VISIBLE)
        assertTrue(renderer.calls.none { it.startsWith("reveal:") }, renderer.calls.toString())
    }

    @Test
    fun clearForViewerDropsRecordsWithoutTouchingOtherPairs() {
        val renderer = FakeRenderer()
        val registry = DisguiseRegistry()
        val other = UUID.nameUUIDFromBytes("other-target".toByteArray())
        val svc = service(renderer, registry = registry)

        svc.apply(viewer, target, EntityViewState.DISGUISED)
        svc.apply(other, viewer, EntityViewState.DISGUISED) // viewer is the target here
        renderer.calls.clear()

        svc.clearFor(viewer)

        // viewer-side records dropped silently, target-side ghosts destroyed
        assertEquals(1, renderer.calls.count { it.startsWith("destroy:") })
        assertEquals(0, registry.all().size)
    }

    @Test
    fun emptyMobRegistryDisablesDisguisingCleanly() {
        val renderer = FakeRenderer()
        val svc = EntityDisguiseService(
            InlineScheduler(), renderer, DisguiseRegistry(), SyntheticEntityIds(), emptyList(),
        )
        svc.apply(viewer, target, EntityViewState.DISGUISED)
        assertTrue(renderer.calls.isEmpty())
    }
}
