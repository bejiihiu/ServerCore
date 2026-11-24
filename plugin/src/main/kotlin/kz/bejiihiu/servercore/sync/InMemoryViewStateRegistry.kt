package kz.bejiihiu.servercore.sync

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * in-memory [StateInvalidator]: viewer -> target -> state, all on concurrent
 * maps so region threads can touch it without locking. this is both the test
 * implementation and a fully functional default — the pe-core registry can
 * replace it behind the same interface whenever it lands.
 */
class InMemoryViewStateRegistry : StateInvalidator {

    private val rows = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, ViewState>>()

    override fun set(viewer: UUID, target: UUID, state: ViewState) {
        rows.computeIfAbsent(viewer) { ConcurrentHashMap() }[target] = state
    }

    override fun get(viewer: UUID, target: UUID): ViewState? = rows[viewer]?.get(target)

    override fun clear(viewer: UUID, target: UUID) {
        rows[viewer]?.let { perTarget ->
            perTarget.remove(target)
            if (perTarget.isEmpty()) rows.remove(viewer, perTarget)
        }
    }

    override fun clearViewer(viewer: UUID) {
        rows.remove(viewer)
    }

    override fun clearTarget(target: UUID) {
        val touched = mutableListOf<UUID>()
        rows.forEach { (viewer, perTarget) ->
            if (perTarget.remove(target) != null) touched.add(viewer)
        }
        touched.forEach { viewer ->
            rows[viewer]?.let { perTarget ->
                if (perTarget.isEmpty()) rows.remove(viewer, perTarget)
            }
        }
    }

    override fun targetsOf(viewer: UUID): Set<UUID> = rows[viewer]?.keys?.toSet() ?: emptySet()

    override fun viewersOf(target: UUID): Set<UUID> =
        rows.filterValues { it.containsKey(target) }.keys

    override fun allRows(): Collection<ViewStateRow> =
        rows.flatMap { (viewer, perTarget) ->
            perTarget.map { (target, state) -> ViewStateRow(viewer, target, state) }
        }

    override fun rowCount(): Int = rows.values.sumOf { it.size }
}
