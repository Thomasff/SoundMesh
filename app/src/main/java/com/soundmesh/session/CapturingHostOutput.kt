package com.soundmesh.session

import android.media.AudioManager
import com.soundmesh.probe.PlaybackUsage

/**
 * The output a host plays on while it is streaming what this phone is playing, and the volume
 * stream that belongs to it.
 *
 * The two are one decision and live on one screen so they cannot drift: a panel showing the level
 * of a stream nothing is playing on would be a control for nothing.
 *
 * Media is impossible here. Capture only reads a stream whose media volume is at zero, and R1
 * proved a media-usage playback is muted along with it - the report said the player ran, routed to
 * the speaker, dropped nothing, and nobody heard a thing.
 *
 * Accessibility was the first answer and turned out to be the same stream as media wearing another
 * scale. On the X10 its level tracks the media level across every reading taken -
 *
 *     media  3  7  4 10   0
 *     a11y   4  8  5 10   0        predicted by 1 + media/15*14: 3.8, 7.5, 4.7, 10.3
 *
 * - and the last column is the one that matters: with media at zero, where capture requires it, the
 * accessibility output sits at its own floor. That is not a volume that happens to be low, it is a
 * volume pinned to the bottom every single session, which is exactly what a listener had been
 * reporting all along as "the accessibility output has always been quiet".
 *
 * Alarm is independent, and measured so: setting it to 12 moved neither of the others, and neither
 * of them moved it. With media at zero and alarm at 12 it plays clearly - the cell R1 and R2 never
 * covered - and the constant it needs is measured like any other, 20.020 ms on the X10 against
 * 20.002 for accessibility, the two non-media outputs being much the same depth.
 *
 * What it costs is honesty about what an alarm usage is. On some handsets alarm output ignores
 * silent mode and do-not-disturb, and this is not an alarm. Acceptable for a project that is not
 * going to a store; it would not be otherwise.
 */
val CAPTURING_HOST_USAGE = PlaybackUsage.ALARM

/** The volume stream [CAPTURING_HOST_USAGE] is heard on. Change one, change the other. */
const val CAPTURING_HOST_STREAM = AudioManager.STREAM_ALARM
