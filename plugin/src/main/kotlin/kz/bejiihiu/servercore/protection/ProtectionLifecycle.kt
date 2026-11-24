package kz.bejiihiu.servercore.protection

import com.mojang.brigadier.CommandDispatcher
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import org.bukkit.plugin.java.JavaPlugin

/**
 * hooks the commands lifecycle event: captures the live brigadier dispatcher
 * for alias analysis and schedules an alias refresh for after the event
 * settles (plugins registering later in the same event would otherwise be
 * missed until the next reload).
 *
 * the lifecycle event refires on every server resource reload, which is
 * exactly when the command tree can change.
 */
class ProtectionLifecycle(
    private val plugin: JavaPlugin,
    private val dispatcherRef: AtomicReference<CommandDispatcher<CommandSourceStack>?>,
    private val onAliasesChanged: () -> Unit,
) {

    fun attach() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            dispatcherRef.set(registrar.dispatcher)
            plugin.server.globalRegionScheduler.runDelayed(
                plugin,
                Consumer { _ -> onAliasesChanged() },
                1L,
            )
        }
    }
}
