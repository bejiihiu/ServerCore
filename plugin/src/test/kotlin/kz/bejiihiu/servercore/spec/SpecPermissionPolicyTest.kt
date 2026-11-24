package kz.bejiihiu.servercore.spec

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** in-memory node table posing as a luckperms backend */
private class FakeLpBackend : PermissionAdapter {

    val nodes = mutableMapOf<Pair<UUID, String>, Boolean?>()

    /** uuids this backend definitively knows have nothing */
    val definitiveNo = mutableSetOf<UUID>()

    override fun checkNode(uuid: UUID, node: String): Boolean? =
        when {
            uuid in definitiveNo -> false
            else -> nodes[uuid to node]
        }

    fun grant(uuid: UUID, node: String, value: Boolean = true) {
        nodes[uuid to node] = value
    }
}

/** always silent backend, for the fall-through test */
private object SilentBackend : PermissionAdapter {
    override fun checkNode(uuid: UUID, node: String): Boolean? = null
}

class SpecPermissionPolicyTest {

    private val levels = listOf("owner", "admin", "moderator", "helper")

    private fun policy(vararg adapters: PermissionAdapter) = SpecPermissionPolicy(
        adapters = adapters.toList(),
        levels = levels,
        specPermission = "servercore.spec",
        fallbackLevelIndex = 3,
    )

    private fun owner() = UUID.randomUUID()
    private fun admin() = UUID.randomUUID()
    private fun moderator() = UUID.randomUUID()
    private fun helper() = UUID.randomUUID()
    private fun nobody() = UUID.randomUUID()

    @Test
    fun `group membership maps to the configured level index`() {
        val backend = FakeLpBackend()
        val owner = owner()
        val admin = admin()
        val helper = helper()
        backend.grant(owner, "group.owner")
        backend.grant(admin, "group.admin")
        backend.grant(helper, "group.helper")

        val p = policy(backend)
        assertEquals(0, p.levelOf(owner))
        assertEquals(1, p.levelOf(admin))
        assertEquals(3, p.levelOf(helper))
    }

    @Test
    fun `highest listed group wins over lower ones`() {
        val backend = FakeLpBackend()
        val promoted = admin()
        backend.grant(promoted, "group.helper")
        backend.grant(promoted, "group.admin")

        assertEquals(1, policy(backend).levelOf(promoted))
    }

    @Test
    fun `the standalone spec permission lands on the fallback level`() {
        val backend = FakeLpBackend()
        val moderator = moderator()
        backend.grant(moderator, "servercore.spec")

        val p = policy(backend)
        assertEquals(3, p.levelOf(moderator))
        assertNull(p.levelOf(nobody()))
    }

    @Test
    fun `group membership outranks the bare spec node`() {
        val backend = FakeLpBackend()
        val admin = admin()
        backend.grant(admin, "servercore.spec")
        backend.grant(admin, "group.admin")

        assertEquals(1, policy(backend).levelOf(admin))
    }

    @Test
    fun `a definitive no from the first backend stops the chain`() {
        val backend = FakeLpBackend()
        val denied = nobody()
        backend.definitiveNo.add(denied)
        // the second backend would happily grant access, the chain must never ask
        val second = FakeLpBackend()
        second.grant(denied, "servercore.spec")

        assertNull(policy(backend, second).levelOf(denied))
    }

    @Test
    fun `a silent backend falls through to the next one`() {
        val granted = moderator()
        val vanilla = FakeLpBackend()
        vanilla.grant(granted, "servercore.spec")

        val p = policy(SilentBackend, vanilla)
        assertEquals(3, p.levelOf(granted))
    }

    @Test
    fun `nobody may observe a strictly higher level`() {
        val p = policy(FakeLpBackend())
        // owner(0) watched by admin(1): no. admin(1) watches moderator(2): yes.
        assertFalse(p.canObserve(1, 0))
        assertTrue(p.canObserve(1, 2))
        assertTrue(p.canObserve(2, 2))
        // a regular player has no level at all, everyone above may watch
        assertTrue(p.canObserve(3, null))
        assertTrue(p.canObserve(0, null))
        assertFalse(p.canObserve(2, 1))
    }

    @Test
    fun `describe maps indexes back to names`() {
        val p = policy(FakeLpBackend())
        assertEquals("owner", p.describe(0))
        assertEquals("admin", p.describe(1))
        assertEquals("unknown", p.describe(99))
    }
}
