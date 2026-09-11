package com.soundmesh.core

/**
 * The short identity one handset is shown under: a two digit number, and a place in the palette.
 *
 * A [HostId] is sixteen hexadecimal characters because of where the value goes - it names files and
 * travels in a space separated code - and nothing about that shape was ever meant to be read off a
 * screen. Four of those characters is what every screen has shown so far, and four hexadecimal
 * characters is not a name: nobody says it out loud, nobody remembers which phone was "3f2a", and
 * a report that says "3f2a stopped playing" is a report that sends somebody to look at both phones.
 *
 * Both halves here are functions of the name and of nothing else, so this handset is the same
 * number on every screen in the room and in a note written a year later. Uniqueness is the price:
 * two of three handsets share a number about three times in a hundred rooms. The colour is what
 * settles those, and it is not decided here - see [RoomColours], which starts from
 * [preferredColour] and moves along until it finds one nobody in the room is using. So the pair is
 * the identity, which is why anything written in words carries both.
 */
object PeerBadge {
    /** How many colours a room can tell apart at a glance. Chosen there, counted here. */
    const val COLOURS = 12

    /**
     * A number for [name], 0 to 99.
     *
     * Two digits because it is said out loud and written by hand, not because two digits are
     * enough to be unique - they are not, and [RoomColours] is the reason that is affordable.
     */
    fun numberOf(name: String): Int = (fold(name) % 100).toInt()

    /**
     * Where in the palette [name] would sit if the room were empty.
     *
     * Taken from the part of the fold the number does not use, so two handsets that collide on a
     * number are no more likely to collide on a colour than any other pair. Reading both off the
     * same digits would make the colour a decoration of the number rather than a second way to
     * tell two handsets apart, and the second way is the whole reason the first one is allowed to
     * collide.
     */
    fun preferredColour(name: String): Int = ((fold(name) / 100) % COLOURS).toInt()

    /**
     * FNV-1a over the whole name, written out rather than delegated to [String.hashCode].
     *
     * Two reasons, and the second is the one that matters. Java specifies what String.hashCode
     * returns so it would not drift - but it is a weak mixer, and these names are hexadecimal, so
     * neighbouring values land in neighbouring buckets and a room of handsets made minutes apart
     * would not be spread by it. This is also a value people will write down; deriving it from
     * something a platform owns is how a number stops meaning the same thing after an upgrade.
     */
    private fun fold(name: String): Long {
        var hash = OFFSET_BASIS
        for (character in name) {
            hash = hash xor (character.code.toLong() and 0xFF)
            hash *= PRIME
        }
        // Sign cleared rather than the remainder's sign fixed afterwards: a negative left here
        // reaches every caller of % as a negative number, and a colour of -3 indexes nothing.
        return hash and Long.MAX_VALUE
    }

    private const val OFFSET_BASIS = -3750763034362895579L // 14695981039346656037 unsigned
    private const val PRIME = 1099511628211L
}
