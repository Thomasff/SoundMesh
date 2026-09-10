package com.soundmesh.core

/**
 * Wire format for a whole spatial rule: the drawing, the mode, and the mode's parameters.
 *
 * Sent once when a session starts and again whenever the listener changes something, never per
 * chunk. What a handset needs per chunk is already in the chunk: the instant it is to be played
 * at. Everything here is what turns that instant into a pair of gains, and it only changes when a
 * person touches the screen.
 *
 * The whole rule travels together rather than the drawing and the mode separately. A handset
 * holding a new drawing and an old mode, or the reverse, would render a room that never existed
 * and has no way to notice; one message means a handset either has the current rule or the
 * previous one, and both of those are rooms somebody drew.
 *
 * Text, hand rolled, one line per handset - the same shape as [AlignmentResultCodec] and for the
 * same reasons: this module carries no third party, and a line of it can be read out of a log.
 */
object SpatialFieldCodec {
    const val MAGIC = "soundmesh-spatial"

    /**
     * Two since the separation knob and the parts joined the rule.
     *
     * Both ends refuse anything else rather than reading what they recognise, which is the whole
     * point of the number: a build that defaulted a missing part to the middle would render a room
     * the sender did not draw while believing it agreed with them.
     */
    const val VERSION = 2

    private const val HEADER_FIELDS = 8
    private const val POSITION_FIELDS = 4

    // Written out rather than taken from an enum because there is no enum: which part a handset
    // carries is a membership of [SpatialField.sideIds], and a two-valued enum beside a set that
    // already says the same thing is a second place for the answer to be wrong.
    private const val MIDDLE = "MIDDLE"
    private const val SIDES = "SIDES"

    fun encode(field: SpatialField): String {
        val lines = ArrayList<String>(field.layout.positions.size + 1)
        lines.add(
            "$MAGIC $VERSION ${field.mode.name} ${field.periodNanos} ${field.pan} " +
                "${field.epochHostNanos} ${field.separation} ${field.layout.positions.size}"
        )
        for (position in field.layout.positions) {
            require(position.peerId.isNotEmpty() && position.peerId.none { it.isWhitespace() }) {
                "a handset name is a wire field: it must be non-empty and carry no whitespace"
            }
            val part = if (position.peerId in field.sideIds) SIDES else MIDDLE
            lines.add("${position.peerId} ${position.x} ${position.y} $part")
        }
        return lines.joinToString("\n")
    }

    /**
     * Parses [text], throwing rather than returning a partial rule.
     *
     * A truncated message is the failure that matters: dropping the handsets that did not arrive
     * would leave the receiver rendering a smaller room than the one on the screen, at full
     * confidence, because a room with fewer handsets in it is a perfectly valid room.
     */
    fun decode(text: String): SpatialField {
        val lines = text.trim().split("\n").map { it.removeSuffix("\r") }
        val header = lines[0].split(" ")
        require(header.size == HEADER_FIELDS && header[0] == MAGIC) { "not a spatial field: ${lines[0]}" }
        require(header[1] == VERSION.toString()) { "unsupported spatial field version: ${header[1]}" }
        val mode = SpatialMode.valueOf(header[2])
        val periodNanos = header[3].toLongOrNull()
            ?: throw IllegalArgumentException("unreadable period: ${header[3]}")
        val pan = header[4].toDoubleOrNull()
            ?: throw IllegalArgumentException("unreadable pan: ${header[4]}")
        val epochHostNanos = header[5].toLongOrNull()
            ?: throw IllegalArgumentException("unreadable epoch: ${header[5]}")
        val separation = header[6].toDoubleOrNull()
            ?: throw IllegalArgumentException("unreadable separation: ${header[6]}")
        val count = header[7].toIntOrNull() ?: throw IllegalArgumentException("unreadable count: ${header[7]}")
        require(count >= 0) { "negative count: $count" }
        require(lines.size == count + 1) {
            "spatial field promised $count handsets and carried ${lines.size - 1}"
        }
        val sideIds = mutableSetOf<String>()
        val positions = (1..count).map { line ->
            val fields = lines[line].split(" ")
            require(fields.size == POSITION_FIELDS) {
                "handset $line has ${fields.size} fields, expected $POSITION_FIELDS"
            }
            when (fields[3]) {
                SIDES -> sideIds += fields[0]
                MIDDLE -> Unit
                else -> throw IllegalArgumentException("unknown part: ${fields[3]}")
            }
            SpatialPosition(
                peerId = fields[0],
                x = fields[1].toDoubleOrNull()
                    ?: throw IllegalArgumentException("unreadable x: ${fields[1]}"),
                y = fields[2].toDoubleOrNull()
                    ?: throw IllegalArgumentException("unreadable y: ${fields[2]}")
            )
        }
        // Both constructors validate. A drawing that could not have been made on the screen is
        // refused here rather than rendered, so a garbled message and a bad drawing fail the same
        // way instead of one of them silently becoming a room.
        return SpatialField(
            mode = mode,
            layout = SpatialLayout(positions),
            periodNanos = periodNanos,
            pan = pan,
            epochHostNanos = epochHostNanos,
            separation = separation,
            sideIds = sideIds
        )
    }
}
