package kz.bejiihiu.servercore.disguise

import java.util.UUID

/**
 * applies visibility decisions to the packet layer. a DISGUISED pair gets a
 * synthetic client-only mob seeded per pair (the real bukkit entity is never
 * modified and no mob exists in the world), VISIBLE gives the real player
 * back, HIDDEN removes the client representation entirely.
 *
 * all calls are idempotent and safe from any thread: entity-state reads are
 * forwarded to the target's entity scheduler through [DisguiseScheduler],
 * packet sending itself is netty-safe.
 */
class EntityDisguiseService(
    private val scheduler: DisguiseScheduler,
    private val renderer: DisguiseRenderer,
    private val registry: DisguiseRegistry,
    private val idSource: SyntheticEntityIds,
    private val mobRegistry: List<String>,
) : ViewStateApplier {

    override fun apply(viewer: UUID, target: UUID, state: EntityViewState) {
        if (viewer == target) return // never disguise yourself to yourself

        when (state) {
            EntityViewState.DISGUISED -> disguise(viewer, target)
            EntityViewState.HIDDEN -> hide(viewer, target)
            EntityViewState.VISIBLE -> reveal(viewer, target)
        }
    }

    /**
     * server-side cleanup for one player leaving (or being wiped): viewers
     * must not keep a synthetic ghost of a departed target, and a departed
     * viewer's records are dropped without packet work.
     */
    fun clearFor(uuid: UUID) {
        for (record in registry.removeForTarget(uuid)) {
            // id 0 means "never spawned", destroying it would hit a real entity
            if (record.syntheticEntityId > 0) {
                renderer.destroyEntities(record.viewer, intArrayOf(record.syntheticEntityId))
            }
        }
        registry.removeForViewer(uuid)
    }

    private fun disguise(viewer: UUID, target: UUID) {
        val existing = registry.get(viewer, target)
        val record = if (existing != null) {
            if (existing.status == DisguiseStatus.DISGUISED) return // already parked
            // was hidden: the same seeded mob comes back, no model swap
            existing.also { it.status = DisguiseStatus.DISGUISED }
        } else {
            val mob = DisguiseChoice.pickOrNull(viewer, target, mobRegistry) ?: return
            ActiveDisguise(
                viewer = viewer,
                target = target,
                syntheticEntityId = idSource.next(),
                mobTypeName = mob,
                realEntityId = 0,
                status = DisguiseStatus.DISGUISED,
            ).also { registry.put(it) }
        }

        scheduler.run(target) { entity ->
            // pair may have been revealed while we waited for the region tick
            if (registry.get(viewer, target) !== record) return@run
            record.realEntityId = entity.entityId
            renderer.spawnDisguise(
                viewer, target, entity.entityId, record.syntheticEntityId,
                SyntheticMobProfile.forMobType(record.mobTypeName), entity.snapshot,
            )
        }
    }

    private fun hide(viewer: UUID, target: UUID) {
        val existing = registry.get(viewer, target)
        if (existing != null) {
            if (existing.status == DisguiseStatus.HIDDEN) return
            existing.status = DisguiseStatus.HIDDEN
            val ids = buildList {
                if (existing.syntheticEntityId > 0) add(existing.syntheticEntityId)
                if (existing.realEntityId > 0) add(existing.realEntityId)
            }.toIntArray()
            if (ids.isNotEmpty()) renderer.destroyEntities(viewer, ids)
            return
        }
        // never touched this pair: park a hidden record so a later reveal
        // knows which real entity to respawn
        val record = ActiveDisguise(viewer, target, 0, "", 0, DisguiseStatus.HIDDEN)
        registry.put(record)
        scheduler.run(target) { entity ->
            if (registry.get(viewer, target) !== record) return@run
            record.realEntityId = entity.entityId
            renderer.destroyEntities(viewer, intArrayOf(entity.entityId))
        }
    }

    private fun reveal(viewer: UUID, target: UUID) {
        val existing = registry.get(viewer, target) ?: return // nothing parked, nothing to do
        registry.remove(viewer, target)
        if (existing.status == DisguiseStatus.DISGUISED && existing.syntheticEntityId > 0) {
            // synthetic gone first, the real spawn goes out after: no flicker
            renderer.destroyEntities(viewer, intArrayOf(existing.syntheticEntityId))
        }
        if (existing.realEntityId <= 0) return // never spawned client-side, nothing to restore
        scheduler.run(target) { entity ->
            // someone re-disguised the pair meanwhile? their flow owns the entity now
            if (registry.get(viewer, target) != null) return@run
            renderer.revealPlayer(viewer, target, entity.entityId, entity.snapshot)
        }
    }
}
