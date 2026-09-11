package com.soundmesh.core

/**
 * Wire format for the one thing about its own identity a handset cannot work out for itself: which
 * colour it holds.
 *
 * The number half needs no message - it is a function of the name, and every handset knows its own.
 * The colour half is taken rather than derived, and taking is something only the room can do, so
 * the handset that shows a colour is never the handset that decided it. Without this a sink can
 * print its number and not its colour, which is half an identity on the screen a person is holding
 * while looking at the drawing on another one.
 *
 * It travels on the rule channel for the same reasons [NowPlayingCodec] does: that channel already
 * knows who each handset is, already remembers what to tell one that joins late, and is nowhere
 * near the timing. Like that one it is a third kind of message on the channel rather than a version
 * of an existing one, so a handset on an older build counts it as unreadable and carries on - it
 * loses the colour and keeps the music.
 *
 * The whole table rather than one handset's own place, because the receiver is the one thing that
 * knows which entry is its own, and a room that could be shown on a sink later is already here.
 */
object RoomBadgeCodec {
    const val MAGIC = "soundmesh-badges"
    const val VERSION = 1

    fun encode(places: Map<String, Int>): String {
        require(places.isNotEmpty()) { "a room has somebody in it" }
        val body = places.entries.joinToString(" ") { (peerId, place) ->
            require(HostId.isValid(peerId)) { "not a handset name: $peerId" }
            require(place in 0 until PeerBadge.COLOURS) { "outside the palette: $place" }
            "$peerId=$place"
        }
        return "$MAGIC $VERSION $body"
    }

    /** True when [text] is one of these rather than some other message on the same channel. */
    fun looksLikeOne(text: String): Boolean = text.startsWith("$MAGIC ")

    /**
     * The table in [text], or throws.
     *
     * Both halves of every entry are checked rather than taken, and both are quiet when wrong: a
     * place outside the palette throws inside a draw call, and a name that is not a name simply
     * never matches this handset's own - which reads as a handset nobody gave a colour to rather
     * than as a message that was wrong.
     */
    fun decode(text: String): Map<String, Int> {
        val parts = text.split(' ')
        require(parts.size >= 3) { "a badge table is a magic, a version and at least one handset" }
        require(parts[0] == MAGIC) { "not a badge table: ${parts[0]}" }
        require(parts[1].toIntOrNull() == VERSION) { "unknown badge table version: ${parts[1]}" }
        return parts.drop(2).associate { entry ->
            val halves = entry.split('=')
            require(halves.size == 2) { "not a handset and a place: $entry" }
            require(HostId.isValid(halves[0])) { "not a handset name: ${halves[0]}" }
            val place = halves[1].toIntOrNull()
            require(place != null && place in 0 until PeerBadge.COLOURS) { "outside the palette: ${halves[1]}" }
            halves[0] to place
        }
    }
}
