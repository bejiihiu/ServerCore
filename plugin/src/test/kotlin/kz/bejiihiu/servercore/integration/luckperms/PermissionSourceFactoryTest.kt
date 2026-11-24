package kz.bejiihiu.servercore.integration.luckperms

import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * runs on a classpath without the luckperms jar (it is compile-only), so
 * create() must degrade to the static fallback instead of blowing up —
 * exactly what happens on a server without the luckperms plugin.
 */
class PermissionSourceFactoryTest {

    @Test
    fun withoutLuckPermsOnTheClasspathTheStaticFallbackTakesOver() {
        val config = LuckPermsConfig(
            fallback = LuckPermsFallbackConfig(
                defaultGroup = "default",
                groups = emptyMap(),
                everyonePermissions = listOf("servercore.spec"),
                playerPermissions = emptyMap(),
            ),
        )

        val source = PermissionSourceFactory.create(config, Logger.getLogger("test"))

        assertEquals("static config fallback", source.sourceName)
        assertTrue(source.hasPermission(
            java.util.UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
            "servercore.spec",
        ).awaitCompleted())
    }
}
