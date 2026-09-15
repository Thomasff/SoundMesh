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
     * Seven since a rule carries how hard the handsets are pushed into playing different
     * waveforms - which is the one knob here that changes what a handset plays rather than
     * which part of it or how loudly, and so the one a receiver silently dropping it would
     * leave sounding right and doing nothing.
     *
     * Both ends refuse anything else rather than reading what they recognise, which is the whole
     * point of the number: a build that defaulted a missing part to the middle would render a room
     * the sender did not draw while believing it agreed with them. The instant is the same case in
     * its own shape - a receiver that dropped it would pick its own moment to swap halves on, and
     * picking your own moment is the fault it exists to remove.
     *
     * The scale is the same case a third time and the loudest of them. A handset that dropped it
     * would default to no arrival delay and go on playing exactly as it did before the delay
     * existed - which is the one failure this whole feature is about, arriving silently, on the
     * one handset of the room whose owner did not update.
     *
     * Eight since a rule carries how far each handset's own output wanders and how far the far
     * side of the room is held back. Both are times, and a receiver that dropped either would play
     * on at no delay - which for the wander is the whole feature missing on one handset while the
     * rest of the room does it, and that is not a room sounding slightly different. It is the two
     * settings whose entire purpose is that the handsets disagree in a way they agreed on.
     *
     * Nine although nothing was added, which is the case this number is least obviously for and is
     * still for. On 09-16 those two times got wider ranges - the wander to twenty milliseconds, its
     * period out to twenty seconds - and [SpatialField] refuses what falls outside them. A build
     * holding the old range and reading a message in the new one would throw somewhere deep in a
     * constructor about a number it cannot name the origin of. Same fields, different meaning, and
     * the version is what turns "unreadable rule" into "that phone needs the new build".
     */
    const val VERSION = 10

    private const val HEADER_FIELDS = 17
    private const val POSITION_FIELDS = 4

    // Written out rather than taken from an enum because there is no enum: which part a handset
    // carries is a membership of [SpatialField.otherHalfIds], and a two-valued enum beside a set that
    // already says the same thing is a second place for the answer to be wrong.
    //
    // Each axis names its own pair, so a handset line read out of a log says what it means without
    // the header beside it - and a message whose header and handsets disagree is caught rather than
    // read as a room. Nothing on the sending side can make one; a spliced message can.
    private const val MIDDLE = "MIDDLE"
    private const val SIDES = "SIDES"
    private const val LOW = "LOW"
    private const val HIGH = "HIGH"

    private fun nearHalfOf(axis: SplitAxis) = when (axis) {
        SplitAxis.MIDDLE_SIDES -> MIDDLE
        SplitAxis.LOW_HIGH -> LOW
    }

    private fun farHalfOf(axis: SplitAxis) = when (axis) {
        SplitAxis.MIDDLE_SIDES -> SIDES
        SplitAxis.LOW_HIGH -> HIGH
    }

    fun encode(field: SpatialField): String {
        val lines = ArrayList<String>(field.layout.positions.size + 1)
        lines.add(
            "$MAGIC $VERSION ${field.mode.name} ${field.periodNanos} ${field.pan} " +
                "${field.epochHostNanos} ${field.separation} ${field.splitAxis.name} " +
                "${field.crossoverHz} ${field.effectiveAtHostNanos} ${field.metresPerUnit} " +
                "${field.envelopment} ${field.diffusion} ${field.travelDelayNanos} " +
                "${field.shimmerDelayNanos} ${field.shimmerPeriodNanos} " +
                "${field.layout.positions.size}"
        )
        for (position in field.layout.positions) {
            require(position.peerId.isNotEmpty() && position.peerId.none { it.isWhitespace() }) {
                "a handset name is a wire field: it must be non-empty and carry no whitespace"
            }
            val part =
                if (position.peerId in field.otherHalfIds) farHalfOf(field.splitAxis)
                else nearHalfOf(field.splitAxis)
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
        val splitAxis = SplitAxis.valueOf(header[7])
        val crossoverHz = header[8].toDoubleOrNull()
            ?: throw IllegalArgumentException("unreadable crossover: ${header[8]}")
        val effectiveAtHostNanos = header[9].toLongOrNull()
            ?: throw IllegalArgumentException("unreadable effective instant: ${header[9]}")
        val metresPerUnit = header[10].toDoubleOrNull()
            ?: throw IllegalArgumentException("unreadable scale: ${header[10]}")
        val envelopment = header[11].toDoubleOrNull()
            ?: throw IllegalArgumentException("unreadable envelopment: ${header[11]}")
        val diffusion = header[12].toDoubleOrNull()
            ?: throw IllegalArgumentException("unreadable diffusion: ${header[12]}")
        val travelDelayNanos = header[13].toLongOrNull()
            ?: throw IllegalArgumentException("unreadable travel delay: ${header[13]}")
        val shimmerDelayNanos = header[14].toLongOrNull()
            ?: throw IllegalArgumentException("unreadable wander depth: ${header[14]}")
        val shimmerPeriodNanos = header[15].toLongOrNull()
            ?: throw IllegalArgumentException("unreadable wander period: ${header[15]}")
        val count = header[16].toIntOrNull() ?: throw IllegalArgumentException("unreadable count: ${header[16]}")
        require(count >= 0) { "negative count: $count" }
        require(lines.size == count + 1) {
            "spatial field promised $count handsets and carried ${lines.size - 1}"
        }
        val otherHalfIds = mutableSetOf<String>()
        val positions = (1..count).map { line ->
            val fields = lines[line].split(" ")
            require(fields.size == POSITION_FIELDS) {
                "handset $line has ${fields.size} fields, expected $POSITION_FIELDS"
            }
            when (fields[3]) {
                farHalfOf(splitAxis) -> otherHalfIds += fields[0]
                nearHalfOf(splitAxis) -> Unit
                else -> throw IllegalArgumentException(
                    "part ${fields[3]} does not belong to a $splitAxis room"
                )
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
            splitAxis = splitAxis,
            crossoverHz = crossoverHz,
            otherHalfIds = otherHalfIds,
            effectiveAtHostNanos = effectiveAtHostNanos,
            metresPerUnit = metresPerUnit,
            envelopment = envelopment,
            diffusion = diffusion,
            travelDelayNanos = travelDelayNanos,
            shimmerDelayNanos = shimmerDelayNanos,
            shimmerPeriodNanos = shimmerPeriodNanos
        )
    }
}
