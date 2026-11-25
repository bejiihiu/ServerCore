package kz.bejiihiu.servercore.spec

import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.Plugin

/**
 * the slice of a live player the session machine mutates.
 *
 * everything here is either a value the machine reads or writes (mode, flight,
 * speeds, position) or a scheduling hop; on folia those belong to the owner's
 * own entity scheduler, which is why the mutations only happen inside
 * [schedule]. the abstraction exists so the state machine can be exercised
 * without a server: [FoliaSpecActor] maps it onto a real [Player], tests map it
 * onto a plain state holder.
 */
interface SpecActor {

    val id: UUID

    val name: String

    val isOnline: Boolean

    /** live position; only meaningful inside [schedule] or right after a hop */
    val location: Location

    var gameMode: GameMode

    var allowFlight: Boolean

    var isFlying: Boolean

    var walkSpeed: Float

    var flySpeed: Float

    /** folia's async teleport; true when the move landed */
    fun teleport(destination: Location, cause: PlayerTeleportEvent.TeleportCause): CompletableFuture<Boolean>

    /** attach the camera to [target] */
    fun spectate(target: SpecActor)

    /** detach the camera */
    fun stopSpectating()

    fun closeInventory()

    /**
     * run [action] on the actor's own entity scheduler - the only correct place
     * to touch it. [onRejected] fires when the entity is retired before the hop
     * lands (the admin quit or the plugin is going down).
     */
    fun schedule(action: () -> Unit, onRejected: (() -> Unit)? = null)
}

/**
 * the server side of the session machine: actor and world lookups. a missing
 * actor means "offline, nothing to touch", never an error - sessions routinely
 * outlive one of the two parties.
 */
interface SpecWorld {

    fun actor(id: UUID): SpecActor?

    /** world-space destination of a journal record, null when that world is not loaded */
    fun journalLocation(record: PendingRestore): Location?
}

/** folia implementation: one live [Player] behind every actor, owned by its entity scheduler */
class FoliaSpecWorld(private val plugin: Plugin) : SpecWorld {

    override fun actor(id: UUID): SpecActor? = plugin.server.getPlayer(id)?.let { FoliaSpecActor(plugin, it) }

    override fun journalLocation(record: PendingRestore): Location? {
        val world = plugin.server.getWorld(record.world) ?: return null
        return Location(world, record.x, record.y, record.z, record.yaw, record.pitch)
    }
}

private class FoliaSpecActor(
    private val plugin: Plugin,
    private val player: Player,
) : SpecActor {

    override val id: UUID get() = player.uniqueId

    override val name: String get() = player.name

    override val isOnline: Boolean get() = player.isOnline

    override val location: Location get() = player.location

    override var gameMode: GameMode
        get() = player.gameMode
        set(value) {
            player.gameMode = value
        }

    override var allowFlight: Boolean
        get() = player.allowFlight
        set(value) {
            player.allowFlight = value
        }

    override var isFlying: Boolean
        get() = player.isFlying
        set(value) {
            player.isFlying = value
        }

    override var walkSpeed: Float
        get() = player.walkSpeed
        set(value) {
            player.walkSpeed = value
        }

    override var flySpeed: Float
        get() = player.flySpeed
        set(value) {
            player.flySpeed = value
        }

    override fun teleport(destination: Location, cause: PlayerTeleportEvent.TeleportCause): CompletableFuture<Boolean> =
        player.teleportAsync(destination, cause)

    override fun spectate(target: SpecActor) {
        val other = (target as? FoliaSpecActor)?.player ?: return
        player.setSpectatorTarget(other)
    }

    override fun stopSpectating() {
        player.setSpectatorTarget(null)
    }

    override fun closeInventory() {
        player.closeInventory()
    }

    override fun schedule(action: () -> Unit, onRejected: (() -> Unit)?) {
        val retired = onRejected?.let { reject -> Runnable { reject() } }
        player.scheduler.run(
            plugin,
            { task ->
                task.cancel()
                action()
            },
            retired,
        )
    }
}
