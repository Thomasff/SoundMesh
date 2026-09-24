package com.soundmesh.product

import com.soundmesh.core.SpatialField
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What the rule is asking of one handset at one instant, written so a person can read it.
 *
 * **Bigger where the sound should be**, on purpose, because that is the one question it exists to
 * answer. It used to be a pair, the second half being how far ahead of the room this handset
 * played, and that half went on 2026-09-17 with the knobs that could move it: every rule left in
 * this room places a source by loudness, so a lead would be zero on every handset of every room.
 */
data class RoomReading(
    val peerId: String,
    /** This handset's gain as a percentage. 100 is what one handset carrying a sound alone plays. */
    val loudness: Int
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
 * With that on screen the vague report becomes a check anybody can run: does the handset the sound
 * seems to be coming from carry the largest number? A yes is the rule reaching the ear it was
 * written for; a no, while the numbers are plainly moving, is worth much more than another evening
 * of turning a slider up.
 */
fun roomReadings(field: SpatialField, hostNanos: Long): List<RoomReading> {
    return field.layout.peerIds.map { peerId ->
        val gain = field.gainAt(peerId, hostNanos)
        RoomReading(
            peerId = peerId,
            // The level of the pair rather than either channel or their mean. A handset carrying
            // one side of a split has a silent channel, and both the mean and a single channel
            // would report that as half as loud as it sounds - which it is not, because the other
            // channel is carrying the whole of what it was given.
            loudness = (sqrt((gain.left * gain.left + gain.right * gain.right) / 2.0) * 100.0)
                .roundToInt()
        )
    }
}
