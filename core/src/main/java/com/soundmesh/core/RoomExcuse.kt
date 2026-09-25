package com.soundmesh.core

/**
 * Why a handset that was told to measure is not going to.
 *
 * The host tells the whole room to measure with one press, and until this existed it learned
 * exactly one thing about what happened next: how many arrived. On 2026-09-13 that cost an
 * evening. One handset had never been given the microphone permission, so it put a system dialog
 * on its own screen and waited for a person who was standing at a different phone. From the host
 * there was nothing to see but "told 3, joined 2" - which is the same thing it shows for a handset
 * that is out of range, one on a slow link, one that never scanned the pairing code, and one whose
 * battery died. Four faults, one screen, and the only way to tell them apart is to walk to each
 * phone, which is the one thing the room channel exists to remove.
 *
 * Named cases rather than free text, and that is the point of the type: the sink is refusing, so
 * it is the end least able to be trusted to compose a sentence, and a string travelling from a
 * phone to a screen is a string nobody checked. Each of these has a fixed sentence on the host,
 * where it can be written in the room's own language.
 */
enum class RoomExcuse {
    /** No microphone permission. Usually a dialog is waiting on that handset's own screen. */
    NO_MICROPHONE,

    /** The link's round trip is past the gate, so no correction measured here would be one. */
    SLOW_LINK,

    /** The clock never settled, which is the same link problem seen from the other end. */
    CLOCK_NOT_CONVERGED,

    /** Already measuring something. A second ask arrives while the first round is still going. */
    BUSY,

    /**
     * Standing by with its screen away, and unable to do the thing that was asked because of it.
     *
     * Nothing on the command list is in that position any more. Measuring was, until 2026-09-15:
     * it drove a screen of its own, and an app in the background may not start an activity at all,
     * so a phone lying face down answered a room round with this. The sink half of a round moved
     * into the standing service and stopped needing a screen, which is why no handset on this
     * build sends it.
     *
     * Kept on the wire rather than removed, for two reasons. A handset still running an older
     * build says it, and a host that could not read it would show that phone as simply absent.
     * And the question every new command has to answer - does obeying this put something on my
     * own screen - still has to have somewhere to land when the answer is yes.
     */
    ASLEEP,

    /**
     * A microphone that opens but hears nothing: muted, its input level at zero, or silenced where
     * nothing can read it. Sent by a computer, whose mute key (F4 on a laptop) leaves the device
     * open and hands back zeros - a round would run to the end and file nothing. One case for all
     * three, because the person's fix is the same place: the system's sound settings.
     */
    MIC_MUTED
}

/**
 * Wire format for one excuse, sent up the standing channel the host already holds open.
 *
 * A frame of its own rather than a field on the announce, for the reason the announce itself is a
 * second frame: a host from before this reads a first frame it cannot make a handset name out of
 * and drops that socket, which is exactly the right thing for it to do with a message it does not
 * speak. The handset saying it loses nothing - it was not going to measure anyway.
 */
object RoomExcuseCodec {
    const val MAGIC = "excuse"

    fun encode(peerId: String, excuse: RoomExcuse): String = "$MAGIC $peerId ${excuse.name}"

    /**
     * The handset and its reason, or null if this is not an excuse at all.
     *
     * Null rather than throwing, because the only caller is deciding which kind of frame it is
     * holding, and "not this kind" is an answer rather than a fault.
     */
    fun decode(text: String): Pair<String, RoomExcuse>? {
        val fields = text.trim().split(" ")
        if (fields.size != 3 || fields[0] != MAGIC) return null
        if (!HostId.isValid(fields[1])) return null
        val excuse = RoomExcuse.entries.firstOrNull { it.name == fields[2] } ?: return null
        return fields[1] to excuse
    }
}
