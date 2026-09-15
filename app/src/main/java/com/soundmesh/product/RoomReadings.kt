package com.soundmesh.product

import com.soundmesh.core.SpatialField
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What the rule is asking of one handset at one instant, written so a person can read it.
 *
 * Both numbers are **bigger where the sound should be**, on purpose, because that is the one
 * question they exist to answer. They get there by the two different routes the room has: a
 * handset can be where the sound is because it is the loudest, or because it is the earliest.
 */
data class RoomReading(
    val peerId: String,
    /** This handset's gain as a percentage. 100 is what one handset carrying a sound alone plays. */
    val loudness: Int,
    /** How far ahead of the room's latest handset this one is, in milliseconds. */
    val leadMillis: Double
)

/**
 * Every handset's pair of numbers at [hostNanos], for the diagnostic strip beside the sliders.
 *
 * Instrumentation, and it was asked for rather than designed in: a listener testing the wander on
 * 09-16 could hear *something* around a third of the slider and had no way to find out whether
 * what they were hearing was the thing the knob claims to do. Ears answer "is there a difference"
 * well and "which difference" badly, and every effect in this room is a claim about where a sound
 * is - so what was missing was not a better ear but the app's own answer, on screen, moving.
 *
 * With that on screen the vague report becomes a check anybody can run: when the left handset's
 * lead is the larger number, does the sound move left? A yes is the precedence effect working in
 * this room, which is the open question the travel knob was built to ask. A no, while the numbers
 * are plainly moving, is that question answered the other way - and is worth much more than
 * another evening of turning a slider up.
 *
 * The lead is the rule's own moving delay only: [SpatialField.playbackDelayNanosFor], which is the
 * travel knob plus the wander. It does not include distance compensation, which is applied to the
 * clock rather than inside the audio, so in a room whose handsets are at different distances and
 * whose compensation is on, this is not the whole of who plays first. For two handsets the same
 * distance away - which is how this is meant to be read - it is.
 */
internal fun roomReadings(field: SpatialField, hostNanos: Long): List<RoomReading> {
    val delays = field.layout.peerIds.associateWith { field.playbackDelayNanosFor(it, hostNanos) }
    val latest = delays.values.maxOrNull() ?: 0L
    return field.layout.peerIds.map { peerId ->
        val gain = field.gainAt(peerId, hostNanos)
        RoomReading(
            peerId = peerId,
            // The level of the pair rather than either channel or their mean. A handset carrying
            // one side of a split has a silent channel, and both the mean and a single channel
            // would report that as half as loud as it sounds - which it is not, because the other
            // channel is carrying the whole of what it was given.
            loudness = (sqrt((gain.left * gain.left + gain.right * gain.right) / 2.0) * 100.0)
                .roundToInt(),
            leadMillis = (latest - delays.getValue(peerId)) / 1_000_000.0
        )
    }
}
