package kz.bejiihiu.servercore.integration.luckperms

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PermissionRecalculationBridgeTest {

    private val uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")

    @Test
    fun firesEverySubscriber() {
        val bridge = PermissionRecalculationBridge()
        val first = mutableListOf<UUID>()
        val second = mutableListOf<UUID>()
        bridge.subscribe { first += it }
        bridge.subscribe { second += it }

        bridge.fire(uuid)

        assertEquals(listOf(uuid), first)
        assertEquals(listOf(uuid), second)
    }

    @Test
    fun aBrokenSubscriberDoesNotKillTheOthers() {
        val bridge = PermissionRecalculationBridge()
        val after = mutableListOf<UUID>()
        bridge.subscribe { throw RuntimeException("boom") }
        bridge.subscribe { after += it }

        bridge.fire(uuid)

        assertEquals(listOf(uuid), after)
    }

    @Test
    fun unsubscribeStopsDelivery() {
        val bridge = PermissionRecalculationBridge()
        val received = mutableListOf<UUID>()
        val subscription = bridge.subscribe { received += it }
        subscription.close()
        bridge.fire(uuid)
        assertTrue(received.isEmpty())
        assertEquals(0, bridge.subscriberCount())
    }
}
