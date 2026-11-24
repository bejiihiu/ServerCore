package kz.bejiihiu.servercore.disguise

import kotlin.test.Test
import kotlin.test.assertEquals

class DisguiseMobsTest {

    @Test
    fun parsesCommaSeparatedNames() {
        assertEquals(listOf("zombie", "skeleton", "cow"), DisguiseMobs.parse("zombie, skeleton, cow"))
    }

    @Test
    fun normalizesCaseAndNamespacePrefix() {
        assertEquals(listOf("zombie", "pig"), DisguiseMobs.parse("ZOMBIE, minecraft:pig"))
    }

    @Test
    fun dropsDuplicatesAndBlanks() {
        assertEquals(listOf("cow", "pig"), DisguiseMobs.parse("cow,, cow, PIG,;cow"))
    }

    @Test
    fun emptyStringGivesEmptyRegistry() {
        assertEquals(emptyList(), DisguiseMobs.parse(""))
        assertEquals(emptyList(), DisguiseMobs.parse("   "))
    }
}
