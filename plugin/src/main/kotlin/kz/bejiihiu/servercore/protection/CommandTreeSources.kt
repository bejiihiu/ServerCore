package kz.bejiihiu.servercore.protection

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.tree.CommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.concurrent.atomic.AtomicReference
import org.bukkit.command.Command
import org.bukkit.command.CommandMap

/**
 * alias sources backed by the real server, all read-only. the bukkit command
 * map groups every registered label by command identity (plugin commands,
 * commands.yml server aliases), the brigadier tree catches vanilla aliases
 * which are registered as redirects (tp -> teleport style). combined they
 * cover what a bare name list cannot.
 *
 * no reflection anywhere: command map access is public paper api, and the
 * dispatcher reference is handed over by the commands lifecycle registrar
 * (reading nodes to build redirects is a documented use case).
 */
object CommandTreeSources {

    /** labels sharing one org.bukkit.command.Command instance are aliases */
    class BukkitCommandMapSource(private val commandMap: () -> CommandMap?) : CommandTreeSnapshot {

        override fun aliasGroups(): Collection<Set<String>> {
            val map = commandMap() ?: return emptyList()
            val byCommand = LinkedHashMap<Command, MutableSet<String>>()
            for ((name, command) in map.knownCommands) {
                if (command == null) continue
                byCommand.getOrPut(command) { LinkedHashSet() }.add(name.lowercase())
            }
            return byCommand.values.filter { it.size > 1 }
        }
    }

    /**
     * vanilla brigadier aliases are redirect nodes in the dispatcher root:
     * union-find over the root literals joins every alias with its target.
     */
    class BrigadierRedirectSource(private val dispatcher: () -> CommandDispatcher<CommandSourceStack>?) :
        CommandTreeSnapshot {

        override fun aliasGroups(): Collection<Set<String>> {
            val root = dispatcher()?.root ?: return emptyList()
            val parent = HashMap<String, String>()
            fun find(a: String): String {
                var current = a
                while (parent[current] != current) current = parent.getValue(current)
                return current
            }
            fun union(a: String, b: String) {
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[ra] = rb
            }

            val children = root.children
            for (child in children) parent.putIfAbsent(child.name.lowercase(), child.name.lowercase())
            for (child in children) {
                val target = effectiveRedirect(child) ?: continue
                val from = child.name.lowercase()
                val to = target.name.lowercase()
                parent.putIfAbsent(to, to)
                union(from, to)
            }
            val groups = LinkedHashMap<String, MutableSet<String>>()
            for (name in parent.keys) groups.getOrPut(find(name)) { LinkedHashSet() }.add(name)
            return groups.values.filter { it.size > 1 }
        }

        /** alias node may redirect directly, or through its single child */
        private fun effectiveRedirect(node: CommandNode<*>): CommandNode<*>? {
            node.redirect?.let { return it }
            val child = node.children.singleOrNull() ?: return null
            return child.redirect
        }
    }

    /**
     * hardcoded floor for the vanilla aliases that must never slip through
     * when both live reads come up empty (e.g. the dispatcher was not captured
     * yet on a fresh boot). small on purpose: only pairs we can vouch for.
     */
    class StaticAliasSource(private val groups: List<Set<String>>) : CommandTreeSnapshot {

        override fun aliasGroups(): Collection<Set<String>> = groups

        companion object {
            /** vanilla registers /tp as a redirect alias of /teleport */
            val VANILLA_ALIAS_GROUPS = listOf(setOf("tp", "teleport"))
        }
    }

    class Combined(private vararg val sources: CommandTreeSnapshot) : CommandTreeSnapshot {

        override fun aliasGroups(): Collection<Set<String>> =
            sources.flatMap { source -> runCatching { source.aliasGroups() }.getOrDefault(emptyList()) }
    }

    /**
     * one snapshot for both the bukkit map and the brigadier dispatcher; the
     * dispatcher reference arrives later via the lifecycle event, so it is
     * passed as an atomic getter.
     */
    fun combined(
        commandMap: () -> CommandMap?,
        dispatcherRef: AtomicReference<CommandDispatcher<CommandSourceStack>?>,
    ): CommandTreeSnapshot = Combined(
        BukkitCommandMapSource(commandMap),
        BrigadierRedirectSource { dispatcherRef.get() },
        StaticAliasSource(StaticAliasSource.VANILLA_ALIAS_GROUPS),
    )
}
