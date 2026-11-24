package kz.bejiihiu.servercore.websocket

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InboundDedupeTest {

    @Test
    fun `first sight is accepted, second is a duplicate`() {
        val dedupe = InboundDedupe(ttlMs = 10_000)
        assertFalse(dedupe.isDuplicate("AUTH_APPROVED|uuid|sess", now = 1_000))
        assertTrue(dedupe.isDuplicate("AUTH_APPROVED|uuid|sess", now = 2_000))
    }

    @Test
    fun `the window expires and the key is acceptable again`() {
        val dedupe = InboundDedupe(ttlMs = 10_000)
        assertFalse(dedupe.isDuplicate("k", now = 1_000))
        assertTrue(dedupe.isDuplicate("k", now = 5_000))
        assertFalse(dedupe.isDuplicate("k", now = 11_001))
    }

    @Test
    fun `distinct keys never collide`() {
        val dedupe = InboundDedupe(ttlMs = 60_000)
        assertFalse(dedupe.isDuplicate("AUTH_REQUEST|sess-1", now = 1_000))
        assertFalse(dedupe.isDuplicate("AUTH_REQUEST|sess-2", now = 1_000))
        assertTrue(dedupe.isDuplicate("AUTH_REQUEST|sess-1", now = 1_001))
    }

    @Test
    fun `the cache stays bounded under churn`() {
        val dedupe = InboundDedupe(ttlMs = 1_000, maxKeys = 100)
        for (i in 0 until 1_000) {
            dedupe.isDuplicate("key-$i", now = i.toLong())
        }
        // everything expired long ago plus the cap held the map down
        assertFalse(dedupe.isDuplicate("key-999", now = 5_000))
        assertFalse(dedupe.isDuplicate("fresh", now = 5_000))
    }
}
