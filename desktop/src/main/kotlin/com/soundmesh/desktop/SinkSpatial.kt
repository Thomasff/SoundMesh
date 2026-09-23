package com.soundmesh.desktop

import com.soundmesh.core.AudioChunk
import com.soundmesh.core.Crossover
import com.soundmesh.core.RoomReverb
import com.soundmesh.core.SpatialField
import com.soundmesh.core.TonePcmSource
import com.soundmesh.probe.sync.ruleInForce
import com.soundmesh.probe.sync.spatialShaped
import com.soundmesh.product.RoomIcon
import com.soundmesh.product.RoomState
import com.soundmesh.product.SpatialRoom

/**
 * A handset host's room, as this machine hears its part of it.
 *
 * The rule comes down the spatial channel, and each chunk is shaped by it here before it is
 * placed, through the same functions a handset's renderer calls - so this machine plays the bytes
 * a handset named [peerId] would, which is what makes it one room rather than a second opinion
 * about one. What is here is the renderer's bookkeeping around those functions and nothing else:
 * which rule the last chunk was heard under, so a new one ramps rather than steps, and the filter
 * and the reverberation, which are this stream's own because they remember what they have heard.
 *
 * How far away this machine was drawn is time as well as level: the chunk comes back later by
 * what the sound takes to cover the difference (see SpatialField.arrivalDelayNanosFor). The
 * handset moves its clock back by that much; moving the chunk forward is the same thing said
 * from this side, where every chunk is placed on its own.
 *
 * [apply] is called from the spatial channel's thread and [shaped] from the audio's. A rule is a
 * whole object replaced at once, as on the handset, so a chunk sees the old room or the new one.
 */
/**
 * What a sink can draw of its host's room: the rule's own drawing turned back into icons, with
 * the colours the host sent. Everything needed to show where this machine stands and what the room
 * is doing, and nothing a sink could change - the drawing is the host's.
 *
 * The inverse of SpatialRoom.layoutOf, which also clamps; an icon comes back where the rule has it.
 */
fun drawnRoomOf(rule: SpatialField, colours: Map<String, Int>, selfId: String): RoomState = RoomState(
    icons = rule.layout.positions.map {
        RoomIcon(it.peerId, SpatialRoom.CENTRE + it.x.toFloat(), SpatialRoom.CENTRE - it.y.toFloat())
    },
    mode = rule.mode,
    pan = rule.pan.toFloat(),
    selfId = selfId,
    colours = colours,
    envelopment = rule.envelopment.toFloat(),
    retreat = rule.retreat.toFloat(),
    reverb = rule.reverb.toFloat(),
    separation = rule.separation.toFloat(),
    splitAxis = rule.splitAxis,
    otherHalfIds = rule.otherHalfIds
)

class SinkSpatial(private val peerId: String) {
    @Volatile private var waiting: SpatialField? = null
    @Volatile private var everReverberated = false

    /** How long this machine holds its sound back so it arrives with the rest, in nanoseconds. */
    @Volatile var arrivalDelayNanos = 0L
        private set

    /** Chunks a rule actually changed, so a run can say whether the room was ever in force here. */
    @Volatile var shapedChunks = 0
        private set

    // Touched only by the audio thread.
    private var inForce: SpatialField? = null
    private var shapedUnder: SpatialField? = null
    private val crossover = Crossover()
    private val reverberation by lazy { RoomReverb(peerId, TonePcmSource.SAMPLE_RATE) }

    /** The newest rule handed to [apply], whether or not a chunk has reached its instant yet. */
    val latest: SpatialField? get() = waiting

    /** The newest rule, to take effect on the chunk it names - SyncRenderer.applySpatialField. */
    fun apply(field: SpatialField?) {
        waiting = field
        if (field == null) inForce = null
        arrivalDelayNanos =
            if (field == null || !field.layout.contains(peerId)) 0L else field.arrivalDelayNanosFor(peerId)
        if (field != null && field.reverb > 0.0) everReverberated = true
    }

    /** [chunk] as this machine should play it, or [chunk] itself when no rule touches it. */
    fun shaped(chunk: AudioChunk): AudioChunk {
        val rule = ruleInForce(inForce, waiting, chunk.playAtHostNanos)
        inForce = rule
        // At the instant the host stamped, not the delayed one: the rule is a function of the
        // host's instant, and every handset evaluates it there.
        val pcm = spatialShaped(
            chunk.sequence,
            chunk.playAtHostNanos,
            chunk.pcm,
            rule,
            peerId,
            wasUnder = shapedUnder,
            crossover = crossover,
            reverb = if (everReverberated) reverberation else null
        )
        shapedUnder = if (pcm !== chunk.pcm) rule else null
        if (pcm !== chunk.pcm) shapedChunks++
        val delay = arrivalDelayNanos
        if (pcm === chunk.pcm && delay == 0L) return chunk
        return AudioChunk(chunk.sequence, chunk.playAtHostNanos + delay, pcm)
    }
}
