package com.soundmesh.core

/**
 * One handset's whole hearing of one room window: where every slot's chirp landed in its own
 * recording, repeat by repeat.
 *
 * A pair's delivery is [AlignmentResultMessage], and it carries readings - the finished arithmetic
 * of the one pair the sender is in. A room cannot send that. The pairs a handset is in are two of
 * the room's N(N-1)/2, and the sender would have to compute the rest from slots it has no business
 * deciding the order of: it does not know which of two other handsets chirped first, and
 * [AlignmentAnalysis.combineFacing] answers a pair with every distance inside out when that is got
 * backwards. So what crosses is the hearing, and the host does all the combining - see
 * [AlignmentAnalysis.facingPairs], whose input is exactly [arrivalsByRepeat] gathered by slot.
 *
 * [ownSlot] rides along because it is the anchor the hearing was read against, not a label on it.
 * The host needs it to know whose hearing this is, and the plan already named it - so a delivery
 * whose slot disagrees with the plan is a handset that read its window against the wrong chirp,
 * which is worth catching rather than averaging in.
 *
 * No applied correction rides along, unlike a pair's delivery. A room measures where the handsets
 * are, and the flight time it measures is the half difference of two recordings, which no clock
 * correction enters. The field a room produces is not folded into anything - the pairwise flow
 * still owns the stored constant - and a number sent here that nothing reads would be free to go
 * wrong with nothing to notice it by.
 */
data class RoomResultMessage(
    val caseId: String,
    val senderId: String,
    val ownSlot: Int,
    val arrivalsByRepeat: List<List<ChirpArrival?>>
)

/**
 * What the host tells one handset once the whole room has been heard and combined.
 *
 * Sent so that delivering into a host that died is not the same event as delivering into one that
 * combined - from the sending end those look identical, which is the lesson the pair channel
 * already carries.
 *
 * [ownPairsReadable] counts the pairs this handset is in, not the room's: a phone showing a number
 * to the person holding it should be showing that person's own result. A room of four answers six
 * pairs and this handset is in three of them, so a 2 here means one of its own neighbours was not
 * heard - which is something the person holding it can act on by moving.
 */
data class RoomReply(val handsets: Int, val ownPairsReadable: Int)

/** Wire format for [RoomReply]: one line, sent back down the same socket. */
object RoomReplyCodec {
    const val MAGIC = "soundmesh-room-reply"
    const val VERSION = 1

    fun encode(reply: RoomReply): String = "$MAGIC $VERSION ${reply.handsets} ${reply.ownPairsReadable}"

    fun decode(text: String): RoomReply {
        val fields = text.trim().split(" ")
        require(fields.size == 4 && fields[0] == MAGIC) { "not a room reply: $text" }
        require(fields[1] == VERSION.toString()) { "unsupported room reply version: ${fields[1]}" }
        val handsets = fields[2].toIntOrNull() ?: throw IllegalArgumentException("unreadable count: ${fields[2]}")
        val readable = fields[3].toIntOrNull() ?: throw IllegalArgumentException("unreadable count: ${fields[3]}")
        require(handsets >= 0 && readable >= 0) { "a room reply counts things: $text" }
        return RoomReply(handsets, readable)
    }
}

/**
 * Wire format for [RoomResultMessage].
 *
 * Its own magic rather than a version of [AlignmentResultCodec], because the two carry different
 * things rather than more of the same: a pair sends finished readings and a room sends raw
 * arrivals, and no reader turns one into the other. A version bump would have made a room's
 * delivery arrive at a pair-shaped reader as a line count that does not add up, which is a report
 * about a truncated stream rather than about a handset running the wrong arm.
 *
 * Text, a line per arrival, with the counts in the header, for the reason the pair codec gives:
 * the sender closes the socket to mark the end, so a connection dropped mid-delivery looks exactly
 * like a short room. A room half of whose slots arrived would answer fewer pairs without saying so.
 */
object RoomResultCodec {
    const val MAGIC = "soundmesh-room"
    const val VERSION = 1

    private const val FIELDS_PER_ARRIVAL = 6
    private const val EDGE_SEPARATOR = ","
    private const val NULL = "null"

    fun encode(message: RoomResultMessage): String {
        requireHeaderField(message.caseId, "caseId")
        requireHeaderField(message.senderId, "senderId")
        val slotCount = message.arrivalsByRepeat.firstOrNull()?.size ?: 0
        require(message.arrivalsByRepeat.all { it.size == slotCount }) {
            "every repeat hears the same room: ${message.arrivalsByRepeat.map { it.size }}"
        }
        require(message.ownSlot >= 0 && (slotCount == 0 || message.ownSlot < slotCount)) {
            "ownSlot ${message.ownSlot} is not one of the $slotCount slots heard"
        }
        val lines = ArrayList<String>(message.arrivalsByRepeat.size * slotCount + 1)
        lines.add(
            "$MAGIC $VERSION ${message.caseId} ${message.senderId} ${message.ownSlot} " +
                "${message.arrivalsByRepeat.size} $slotCount"
        )
        for (repeat in message.arrivalsByRepeat) {
            for (arrival in repeat) lines.add(arrival.line())
        }
        return lines.joinToString("\n")
    }

    fun decode(text: String): RoomResultMessage {
        val lines = text.split("\n").map { it.removeSuffix("\r") }
        val header = lines[0].split(" ")
        require(header.size == 7 && header[0] == MAGIC) { "not a room result: ${lines[0]}" }
        require(header[1] == VERSION.toString()) { "unsupported room result version: ${header[1]}" }
        val ownSlot = header[4].toCount("slot")
        val repeats = header[5].toCount("repeat count")
        val slotCount = header[6].toCount("slot count")
        require(lines.size == repeats * slotCount + 1) {
            "a room result promised ${repeats * slotCount} arrivals and carried ${lines.size - 1}"
        }
        require(slotCount == 0 || ownSlot < slotCount) {
            "ownSlot $ownSlot is not one of the $slotCount slots heard"
        }
        val arrivals = (0 until repeats).map { repeat ->
            (0 until slotCount).map { slot -> lines[1 + repeat * slotCount + slot].toArrival() }
        }
        return RoomResultMessage(header[2], header[3], ownSlot, arrivals)
    }

    private fun ChirpArrival?.line(): String = this?.let {
        listOf(
            it.index.toString(),
            it.peak.toString(),
            it.floor.toString(),
            it.ratio.toString(),
            it.atSearchEdge.toString(),
            it.edgeIndices.joinToString(EDGE_SEPARATOR).ifEmpty { NULL }
        ).joinToString(" ")
    } ?: NULL

    private fun String.toArrival(): ChirpArrival? {
        if (this == NULL) return null
        val fields = split(" ")
        require(fields.size == FIELDS_PER_ARRIVAL) {
            "an arrival has ${fields.size} fields, expected $FIELDS_PER_ARRIVAL: $this"
        }
        return ChirpArrival(
            index = fields[0].toIntField(),
            peak = fields[1].toDoubleField(),
            floor = fields[2].toDoubleField(),
            ratio = fields[3].toDoubleField(),
            atSearchEdge = fields[4].toBooleanField(),
            edgeIndices =
                if (fields[5] == NULL) emptyList() else fields[5].split(EDGE_SEPARATOR).map { it.toIntField() }
        )
    }

    private fun requireHeaderField(value: String, name: String) {
        require(value.isNotEmpty() && value.none { it.isWhitespace() }) {
            "$name must be non-empty and carry no whitespace: it is a header field"
        }
    }

    private fun String.toCount(name: String): Int {
        val value = toIntOrNull() ?: throw IllegalArgumentException("unreadable $name: $this")
        require(value >= 0) { "negative $name: $value" }
        return value
    }

    private fun String.toIntField(): Int =
        toIntOrNull() ?: throw IllegalArgumentException("unreadable integer: $this")

    private fun String.toDoubleField(): Double =
        toDoubleOrNull() ?: throw IllegalArgumentException("unreadable number: $this")

    private fun String.toBooleanField(): Boolean = when (this) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("unreadable boolean: $this")
    }
}
