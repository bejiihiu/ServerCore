package kz.bejiihiu.servercore.disguise

import java.util.UUID
import kotlin.random.Random

/**
 * seeded mob pick for a (viewer, target) pair. the same pair always lands on
 * the same mob, so a client resync respawns the same model instead of a
 * different one popping in mid-game. randomness comes from the pair
 * identities alone, never the clock, hence stable across resyncs and reboots.
 */
object DisguiseChoice {

    /** deterministic per-pair pick, null when nothing is configured */
    fun pickOrNull(viewer: UUID, target: UUID, registry: List<String>): String? {
        if (registry.isEmpty()) return null
        val seed = mix(viewer.mostSignificantBits, viewer.leastSignificantBits) xor
            mix(target.leastSignificantBits, target.mostSignificantBits)
        // Random(seed) is a spec-fixed algorithm, so the pick is stable
        // across jvm runs too, not just within one process
        return registry[Random(seed).nextInt(registry.size)]
    }

    private const val GOLDEN = 0x9E3779B97F4A7C15uL
    private const val MIX1 = 0xBF58476D1CE4E5B9uL
    private const val MIX2 = 0x94D049BB133111EBuL

    // splitmix64 finalizer: spreads the uuid bits before they hit the seed so
    // neighboring uuid pairs don't produce correlated picks
    private fun mix(a: Long, b: Long): Long {
        var z = a.toULong() xor (b.toULong() * GOLDEN)
        z = (z xor (z shr 30)) * MIX1
        z = (z xor (z shr 27)) * MIX2
        return (z xor (z shr 31)).toLong()
    }
}
