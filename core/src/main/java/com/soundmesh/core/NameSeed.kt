package com.soundmesh.core

/**
 * Turns a handset's name into numbers that differ between handsets and never travel between them.
 *
 * Two things here draw their shape from a name rather than from a message - the decorrelator's
 * allpass lengths and the reverberation's comb lengths - and both want the same property: two names
 * that look alike must not give filters that sound alike. Written once because this project has
 * already been bitten by a third copy of something it had two of; see the 09-11 note on models
 * drifting apart in [SpatialShaper]'s neighbours.
 */
internal object NameSeed {
    /**
     * FNV-1a over the name's bytes, then a bit mixer.
     *
     * Hand rolled rather than String.hashCode() because that is thirty-two bits of a weak function
     * over names that share long prefixes, and the two handsets most likely to collide are the two
     * most likely to be in the same room.
     */
    fun of(peerId: String): Long {
        var hash = -3750763034362895579L
        for (byte in peerId.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 1099511628211L
        }
        return hash
    }

    /** splitmix64's finaliser: avalanches the low bits, which is the half a modulo reads. */
    fun mix(state: Long): Long {
        var z = state + -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    /** A draw in `0 until bound`, from [seed] advanced by [step]. */
    fun pick(seed: Long, step: Int, bound: Int): Int = ((mix(seed + step) ushr 1) % bound).toInt()
}
