package kz.bejiihiu.servercore.disguise

/**
 * the synthetic characteristics of a client-only disguise entity. everything
 * here is plausible mob defaults; real player state (armor, held item,
 * health, skin layers) never enters this shape.
 */
enum class DisguiseMetadataKind { BYTE, INT, FLOAT }

data class MobMetadataEntry(val index: Int, val kind: DisguiseMetadataKind, val value: Any)

enum class DisguiseEquipmentSlot {
    MAIN_HAND,
    OFF_HAND,
    BOOTS,
    LEGGINGS,
    CHESTPLATE,
    HELMET,
    BODY,
}

data class SyntheticMobProfile(
    val mobTypeName: String,
    val metadata: List<MobMetadataEntry>,
    /** equipment slots explicitly cleared on the synthetic entity */
    val clearedEquipment: List<DisguiseEquipmentSlot>,
) {
    companion object {

        /**
         * appearance for a freshly spawned client-only mob: zero player state.
         * metadata carries only the shared entity flags byte forced to 0
         * (not on fire / not crouching / nothing); health, skin layers and
         * every other player-only field are simply absent, so the client
         * renders the mob's own defaults. the equipment packet then clears
         * every wearable slot explicitly, so even a resynced client cannot
         * end up with the player's sword on a cow.
         */
        fun forMobType(mobTypeName: String): SyntheticMobProfile = SyntheticMobProfile(
            mobTypeName = mobTypeName,
            metadata = listOf(MobMetadataEntry(index = 0, kind = DisguiseMetadataKind.BYTE, value = 0.toByte())),
            clearedEquipment = DisguiseEquipmentSlot.entries.toList(),
        )
    }
}
