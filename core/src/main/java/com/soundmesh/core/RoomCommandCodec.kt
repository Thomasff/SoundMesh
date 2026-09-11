package com.soundmesh.core

/**
 * What a host can ask the rest of the room to do without anybody touching those handsets.
 *
 * Deliberately four things and not a remote control. Everything here is something that otherwise
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
    MEASURE_OVERHEAD
}

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

    fun encode(command: RoomCommand): String = "$MAGIC $VERSION ${command.name}"

    /** The command in [text], or throws. */
    fun decode(text: String): RoomCommand {
        val parts = text.trim().split(" ")
        require(parts.size == 3) { "not a command: $text" }
        require(parts[0] == MAGIC) { "not a command: ${parts[0]}" }
        require(parts[1] == VERSION.toString()) {
            "a command in version ${parts[1]}, and this build speaks $VERSION"
        }
        return RoomCommand.values().firstOrNull { it.name == parts[2] }
            ?: throw IllegalArgumentException("no such command: ${parts[2]}")
    }
}
