package com.soundmesh.probe.sync

import com.soundmesh.core.ChunkCodec
import com.soundmesh.core.Crossover
import com.soundmesh.core.RoomReverb
import com.soundmesh.core.SpatialField
import com.soundmesh.core.SpatialShaper
import com.soundmesh.core.SpectrumMix
import com.soundmesh.core.StereoGain
import com.soundmesh.core.TonePcmSource

// Moved out of SyncRenderer.kt on 2026-09-23, package unchanged, so the desktop sink shapes a chunk
// through the same functions a handset does rather than through a second copy of them.

/**
 * One chunk of PCM as the spatial rule wants it heard, or the chunk itself when no rule applies.
 *
 * Kept out of [SyncRenderer] so it can be tested without an AudioTrack, the same reason
 * [trackProfileJson] sits out here. The gain law is core's; what is decided here is which chunks
 * it is allowed near.
 *
 * Chirp chunks are never shaped. The chirp is the instrument every alignment number is measured
 * with, and a gain on it moves the correlation peak and the between-handset ratios the verdict is
 * read from - while sounding like a room working correctly. This is the same exemption the trim
 * deadband and the splice fade already take, for the same reason.
 *
 * A handset the drawing does not name plays on unshaped rather than going silent. Absence means a
 * stale rule or a bug, and the two answers are not symmetric: playing on is the room behaving as
 * it did before spatial audio existed, while a silent handset is one dropping out of a room the
 * listener is still looking at, with nothing on screen saying why.
 */
/**
 * Which rule a chunk heard at [playAtHostNanos] is played under.
 *
 * [waiting] is the newest rule this handset has been told about and [inForce] is the one its
 * last chunk was played under. A rule stamped with an instant is held back until a chunk that
 * is heard at or after it, so every handset in the room swaps on the same chunk however far
 * apart they were told - which is the whole of what this buys. See
 * [com.soundmesh.core.SpatialField.effectiveAtHostNanos] for what the disagreement costs and
 * why only the large changes are worth this.
 *
 * A rule whose instant has already passed is taken at once, so a handset told late lands on the
 * behaviour every handset had before this existed rather than on something worse.
 */
fun ruleInForce(
    inForce: SpatialField?,
    waiting: SpatialField?,
    playAtHostNanos: Long
): SpatialField? =
    if (waiting != null && playAtHostNanos >= waiting.effectiveAtHostNanos) waiting else inForce

fun spatialShaped(
    sequence: Int,
    playAtHostNanos: Long,
    payload: ByteArray,
    field: SpatialField?,
    peerId: String?,
    wasUnder: SpatialField? = null,
    crossover: Crossover,
    reverb: RoomReverb? = null
): ByteArray {
    if (field == null || peerId == null) return payload
    if (sequence >= ChunkCodec.CHIRP_SEQUENCE_BASE) return payload
    if (!field.layout.contains(peerId)) return payload
    return SpatialShaper.shape(
        payload,
        field,
        peerId,
        playAtHostNanos,
        TonePcmSource.SAMPLE_RATE,
        from = cameFrom(wasUnder, field, peerId, playAtHostNanos),
        fromFold = foldCameFrom(wasUnder, field, peerId),
        fromSpectrum = spectrumCameFrom(wasUnder, field, peerId),
        fromRoom = roomCameFrom(wasUnder, field, peerId, playAtHostNanos),
        fromReverb = reverbCameFrom(wasUnder, field, peerId),
        crossover = crossover,
        reverb = reverb
    )
}

/**
 * Where the room was a moment ago, when that is not where this rule says it is.
 *
 * Null whenever the previous chunk was already under this same rule, which is every chunk of
 * ordinary playback - the law is continuous, so its value at this chunk's first instant is exactly
 * where the previous chunk left off and the ramp needs no help.
 *
 * The three cases that are not that: the first rule arriving at a handset that has been playing
 * unshaped, a rule being replaced by a different one, and an icon being dragged - which publishes a
 * new rule several times a second. In all three the gain steps rather than moves, and the step is
 * the whole gain: up to unity, sixty times the 1.64% a chunk edge is worth. That is the one the
 * room can hear, and it is what a listener reported as a noise in the first second of a session.
 *
 * Unity for the handset that was playing under no rule at all, because that is what it was heard
 * at. A handset the old rule did not name is the same case.
 */
private fun cameFrom(
    wasUnder: SpatialField?,
    now: SpatialField,
    peerId: String,
    playAtHostNanos: Long
): StereoGain? {
    if (wasUnder === now) return null
    if (wasUnder == null || !wasUnder.layout.contains(peerId)) return StereoGain(1.0, 1.0)
    return wasUnder.gainAt(peerId, playAtHostNanos)
}

/**
 * How much of the other channel the room was folding in a moment ago, when that is not what this
 * rule asks for.
 *
 * A second function rather than a second return value from [cameFrom] because the two answer to
 * different controls: dragging an icon moves the gain and leaves the fold where it was, dragging
 * the separation knob does the reverse. Sharing one would make each of them ramp whenever the
 * other did, which is a step at a chunk edge dressed as a smoothing.
 *
 * Zero for a handset that was playing under no rule at all, because folding in none of the other
 * channel is exactly what playing the mix unshaped is. A handset the old rule did not name is the
 * same case, for the same reason it is in [cameFrom].
 */
private fun foldCameFrom(wasUnder: SpatialField?, now: SpatialField, peerId: String): Double? {
    if (wasUnder === now) return null
    if (wasUnder == null || !wasUnder.layout.contains(peerId)) return 0.0
    return wasUnder.foldFor(peerId)
}

/**
 * What share of the room's own reverberation this handset was carrying a moment ago.
 *
 * [cameFrom] for the wet, and it needs its own because the wet's placement is not the dry's: the
 * distance is left out of it, which is the whole of how a reverberation reads as a source moving
 * away. Silence for a handset that was playing under no rule, because a handset playing unshaped is
 * playing no room at all.
 */
private fun roomCameFrom(
    wasUnder: SpatialField?,
    now: SpatialField,
    peerId: String,
    playAtHostNanos: Long
): StereoGain? {
    if (wasUnder === now) return null
    if (wasUnder == null || !wasUnder.layout.contains(peerId)) return StereoGain(0.0, 0.0)
    return wasUnder.roomGainAt(peerId, playAtHostNanos)
}

/**
 * How much room the previous chunk was heard ending in, when that is not what this rule asks for.
 *
 * The one of these that needs nobody named - how live the room is is one number for all of it. It
 * is separate from [roomCameFrom] for the same reason the fold is separate from [cameFrom]: turning
 * the room up moves this and leaves the placement where it was, and dragging an icon does the
 * reverse. Zero for a handset that was playing under no rule, because a room nobody asked for is no
 * room.
 */
private fun reverbCameFrom(wasUnder: SpatialField?, now: SpatialField, peerId: String): Double? {
    if (wasUnder === now) return null
    if (wasUnder == null || !wasUnder.layout.contains(peerId)) return 0.0
    return wasUnder.reverb
}

/**
 * How much of the mix and of its low half the room was playing a moment ago.
 *
 * The third of these, for the third control, and separate for the reason the other two are: the
 * axis can be swapped under a handset, which moves this and the fold at once and leaves the gain
 * where it stands.
 *
 * The whole mix and none of the filter for a handset that was under no rule, because playing the
 * mix unshaped is exactly that. A swap of axis therefore ramps out of one division and into the
 * other across a chunk rather than cutting between them.
 */
private fun spectrumCameFrom(wasUnder: SpatialField?, now: SpatialField, peerId: String): SpectrumMix? {
    if (wasUnder === now) return null
    if (wasUnder == null || !wasUnder.layout.contains(peerId)) return SpectrumMix(1.0, 0.0)
    return wasUnder.spectrumFor(peerId)
}
