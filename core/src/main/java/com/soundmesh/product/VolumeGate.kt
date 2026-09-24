package com.soundmesh.product

/**
 * How quiet a handset may be before a round is refused, as a percentage of its own scale.
 *
 * A percentage rather than an index because handsets do not agree on how many steps a stream has -
 * fifteen on one of these, sixteen on the next - see `indexFor`.
 *
 * Ten is not a measured threshold and does not pretend to be one. What it is is the line below
 * which a round measures the room's noise floor instead of the room: every handset has to be heard
 * by every other one, and a phone at one step of fifteen is not heard across a room by anything.
 * The number people actually play at is theirs to choose - this only refuses the settings that
 * cannot work.
 */
const val QUIET_FLOOR_PERCENT = 10

/**
 * Which handsets are too quiet for a round, by name, in the order they are drawn.
 *
 * Judged on [VolumeRow.percent] - what the handset read back off its own stream - and never on
 * [VolumeRow.asked], which is what somebody told it to be. `setStreamVolume` has been seen on this
 * project's own handsets to take a value, throw nothing and move nothing, and a gate that trusted
 * the instruction would wave through the one handset that is actually silent. That is also why the
 * answer is names rather than a count: what a person does about it is walk to one particular phone.
 */
fun tooQuietFor(rows: List<VolumeRow>, floor: Int = QUIET_FLOOR_PERCENT): List<String> =
    rows.filter { it.percent < floor }.map { it.name }

/**
 * Whether leaving the calibration should put the room's volume back where it was.
 *
 * Only when this screen is what moved it. The volume a room plays at is set on the playing screen
 * as well, and that screen has a restore of its own; both of them put back the level from before
 * the app ever touched the handset, so a calibration that restored unconditionally would throw
 * away the level somebody had chosen for the music and leave them wondering which screen did it.
 *
 * [changedBefore] is what `HandsetVolume.changed` said as this screen opened, [changedNow] what it
 * says as it closes.
 */
fun restoresOnLeaving(changedBefore: Boolean, changedNow: Boolean): Boolean =
    !changedBefore && changedNow
