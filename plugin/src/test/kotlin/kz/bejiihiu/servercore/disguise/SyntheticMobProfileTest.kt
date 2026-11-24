package kz.bejiihiu.servercore.disguise

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyntheticMobProfileTest {

    @Test
    fun metadataCarriesOnlyTheSharedFlagsByte() {
        val profile = SyntheticMobProfile.forMobType("zombie")

        // one entry, and it is the version-stable shared entity flags byte at
        // index 0, forced to 0. no health, no skin layers, nothing player-only
        assertEquals(listOf(0), profile.metadata.map { it.index })
        assertEquals(DisguiseMetadataKind.BYTE, profile.metadata.single().kind)
        assertEquals(0.toByte(), profile.metadata.single().value)
    }

    @Test
    fun noPlayerOnlyMetadataLeakIndex() {
        val profile = SyntheticMobProfile.forMobType("cow")

        // the classic player leak surfaces: health (1), skin layers (17),
        // pose (6). none of them may appear on a fake mob
        val leaked = setOf(1, 6, 17)
        assertTrue(profile.metadata.none { it.index in leaked })
    }

    @Test
    fun everyWearableSlotIsCleared() {
        val profile = SyntheticMobProfile.forMobType("skeleton")

        assertEquals(
            setOf(
                DisguiseEquipmentSlot.MAIN_HAND,
                DisguiseEquipmentSlot.OFF_HAND,
                DisguiseEquipmentSlot.BOOTS,
                DisguiseEquipmentSlot.LEGGINGS,
                DisguiseEquipmentSlot.CHESTPLATE,
                DisguiseEquipmentSlot.HELMET,
                DisguiseEquipmentSlot.BODY,
            ),
            profile.clearedEquipment.toSet(),
        )
    }

    @Test
    fun profileIsPureAndRepeatable() {
        assertEquals(SyntheticMobProfile.forMobType("pig"), SyntheticMobProfile.forMobType("pig"))
        // the mob name travels with the profile so the renderer needs no side channel
        assertEquals("pig", SyntheticMobProfile.forMobType("pig").mobTypeName)
    }
}
