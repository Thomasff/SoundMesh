package com.soundmesh.core

/**
 * What a host can ask the rest of the room to do without anybody touching those handsets.
 *
 * Deliberately a short list and not a remote control. Everything here is something that otherwise
 * has to be done by walking to each phone in turn and pressing the same button on it, in an order
 * that matters and that nobody can see - which is the one part of this project a listener has
 * called unusable rather than imperfect.
 */
enum class RoomCommand {
    /** Start playing what the host is playing. Ignored by a handset already playing. */
    PLAY,

    /** Stop. Ignored by a handset that is not playing. */
    STOP,

    /** Go and measure the distances between every handset in the room. */
    MEASURE_ROOM,

    /** The same round, with the listener's own position in it: see the overhead hint on screen. */
    MEASURE_OVERHEAD,

    /**
     * Go and measure against this host alone, the pair round rather than the room one.
     *
     * Said to one handset - see `RoomCommandServer.sendTo` - because that is what it means: the
     * pair round is between this host and one sink, and a room told all at once would be several
     * handsets trying to hold the same clock socket.
     *
     * Only the host can start one. Until this existed the person had to press start on the sink
     * as well, having already pressed it here, and the two presses had to land in the right order
     * for anything to happen at all.
     */
    MEASURE_PAIR,

    /**
     * Set the volume of whichever stream this handset is actually playing on, as a percentage.
     *
     * A percentage rather than an index because handsets do not agree on how many steps a stream
     * has - fifteen on one, sixteen on the next - so an index means a different loudness on each
     * of them and a room set to "9" is a room set to nothing in particular.
     */
    SET_VOLUME,

    /** Put back whatever this handset's volume was before anything here first changed it. */
    RESTORE_VOLUME,

    /**
     * Stop the round you are in, now, and keep nothing of it.
     *
     * The one command that is about a round already running rather than about starting one, which
     * is why the sink end answers it before the guard that turns away everything else said to a
     * measuring handset - see `StandbyService.obey`. Until this existed the host could only call a
     * round off while the handsets were still waiting for a plan: once the schedule was out every
     * one of them was acting on its own clock, and a stop button pressed then reached nobody.
     *
     * Additive on the wire in the direction that matters: a handset on an older build has no name
     * for this and refuses the line whole, which leaves it doing exactly what it did before -
     * playing the round out.
     */
    CALL_OFF
}

/**
 * One command and the single number some of them carry.
 *
 * A number rather than a payload, and one rather than several: the moment this grows a shape it
 * is a remote control protocol, and what is wanted is the short list above.
 */
data class RoomOrder(val command: RoomCommand, val value: Int? = null)

/**
 * Wire format for one command, on its own channel.
 *
 * A command is an event and not a state, which is the one thing to keep hold of here. The spatial
 * rule is remembered by its server so a handset joining late is told the current one; a command
 * must not be, or a sink that walks into the room ten minutes later starts playing on its own
 * because that is what the host said once. Nothing on this channel is replayed to a late joiner.
 *
 * Refused outright on a version it does not know, on the same terms as [SpatialFieldCodec]: a
 * reader that acted on "the part it understood" would be a handset doing something nobody asked
 * it to do, which is worse than a handset that did nothing.
 */
object RoomCommandCodec {
    const val MAGIC = "soundmesh-command"
    const val VERSION = 1

    fun encode(order: RoomOrder): String =
        "$MAGIC $VERSION ${order.command.name}" + (order.value?.let { " $it" } ?: "")

    /** The order in [text], or throws. */
    fun decode(text: String): RoomOrder {
        val parts = text.trim().split(" ")
        // Three or four: the number is a later addition, and a build from before it reads a
        // four-part line as no command at all - which is the right answer for a handset that
        // cannot do what is being asked. See the version note above for why this is not a bump:
        // bumping would make an old handset refuse PLAY as well, and PLAY is the whole product.
        require(parts.size in 3..4) { "not a command: $text" }
        require(parts[0] == MAGIC) { "not a command: ${parts[0]}" }
        require(parts[1] == VERSION.toString()) {
            "a command in version ${parts[1]}, and this build speaks $VERSION"
        }
        val command = RoomCommand.values().firstOrNull { it.name == parts[2] }
            ?: throw IllegalArgumentException("no such command: ${parts[2]}")
        val value = if (parts.size == 4) {
            parts[3].toIntOrNull() ?: throw IllegalArgumentException("not a number: ${parts[3]}")
        } else null
        return RoomOrder(command, value)
    }
}
