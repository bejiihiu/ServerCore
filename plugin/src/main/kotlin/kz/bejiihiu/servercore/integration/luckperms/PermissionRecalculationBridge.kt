package kz.bejiihiu.servercore.integration.luckperms

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * notifies subscribers when a player's permission data was recalculated.
 *
 * the luckperms-side cache is lp's own (we do not re-implement caching), so
 * this bridge exists purely as an invalidation seam: later subsystems that
 * keep derived state around a player's permissions (spec mode, protection
 * snapshots) subscribe here instead of polling. no-op when luckperms is
 * absent — the static fallback never changes at runtime.
 */
class PermissionRecalculationBridge {

    private val subscribers = CopyOnWriteArrayList<(UUID) -> Unit>()

    /**
     * registers a subscriber, returns an unsubscribe handle. callbacks fire
     * on whatever thread luckperms recalculated on — offload before touching
     * world state.
     */
    fun subscribe(subscriber: (UUID) -> Unit): AutoCloseable {
        subscribers += subscriber
        return AutoCloseable { subscribers.remove(subscriber) }
    }

    /** fires every subscriber for [uuid]; one broken subscriber never breaks the rest */
    fun fire(uuid: UUID) {
        for (subscriber in subscribers) {
            try {
                subscriber(uuid)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                // contained: a misbehaving subscriber must not kill the others
            }
        }
    }

    fun subscriberCount(): Int = subscribers.size
}
