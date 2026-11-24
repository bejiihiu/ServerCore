package kz.bejiihiu.servercore.boot

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ServerCoreCommandTest {

    private fun tree(): com.mojang.brigadier.tree.LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> =
        ServerCoreCommand(
            groups = { null },
            deopAll = { null },
            runOnGlobalThread = { task -> task.run() },
        ).build()

    @Test
    fun `one servercore root carries both subcommands`() {
        val root = tree()
        assertEquals("servercore", root.name)
        assertEquals(setOf("groups", "deop-all"), root.children.map { it.name }.toSet())
    }

    @Test
    fun `groups takes an optional player argument`() {
        val groups = tree().getChild("groups")
        assertEquals(setOf("player"), groups.children.map { it.name }.toSet())
    }

    @Test
    fun `no duplicate subcommands sneak back in`() {
        val names = tree().children.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }
}
