package kz.bejiihiu.servercore.protection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PermissionsTest {

    @Test
    fun `permission nodes match the agreed names exactly`() {
        assertEquals("servercore.admin", Permissions.ADMIN)
        assertEquals("servercore.spec", Permissions.SPEC)
        assertEquals("servercore.spec.other", Permissions.SPEC_OTHER)
        assertEquals("servercore.guest.manage", Permissions.GUEST_MANAGE)
        assertEquals("servercore.account.manage", Permissions.ACCOUNT_MANAGE)
    }
}
