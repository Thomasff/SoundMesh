package com.soundmesh.probe.sync

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Holds what this handset has to say up a line, and says it on a thread of its own.
 *
 * It exists because of which thread asks. Everything a handset says on its standing line is
 * prompted by a screen or by a command that arrived through one: the volume keys are polled from a
 * timer, the still-here beat hangs off that same timer, and being told to set a volume is answered
 * with what the volume actually came to. Android will not write to a socket from the thread that
 * draws the screen - it throws NetworkOnMainThreadException rather than blocking - so every one of
 * those was refused, silently as far as anybody watching a phone could tell, and the host let the
 * handset go every eight seconds for saying nothing. The excuse channel had had this right all
 * along, on its own thread; the standing line was the one place that wrote where it stood.
 *
 * A queue with an end to it rather than one that grows, because the thing on the far side of the
 * socket may be a handset that walked out of the network - and a write to one of those does not
 * fail, it waits in the kernel for minutes. A caller who is refused can say so; a caller stuck
 * inside a write cannot.
 */
class ThingsToSay(
    private val name: String,
    private val room: Int = ROOM,
    private val write: (String) -> Unit
) : AutoCloseable {
    private val queue = ArrayBlockingQueue<String>(room)

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ keepSaying() }, name).also { it.start() }
    }

    /** Hands one thing over, and answers whether there was anywhere to put it. */
    fun say(what: String): Boolean = running && queue.offer(what)

    /** Drops what is waiting, which is what everything queued for a dead socket is worth. */
    fun forget() = queue.clear()

    private fun keepSaying() {
        while (running) {
            // Polled with a timeout rather than taken, so that closing is noticed by a line that
            // has had nothing to say for a while - which is most of them, most of the time.
            val what = runCatching { queue.poll(LOOK_EVERY_MILLIS, TimeUnit.MILLISECONDS) }
                .getOrNull() ?: continue
            // Guarded here rather than at the caller: whoever handed this over is long gone, and
            // a write that throws is the line's business, not theirs.
            runCatching { write(what) }
        }
    }

    override fun close() {
        running = false
        thread?.interrupt()
        thread = null
        queue.clear()
    }

    private companion object {
        /**
         * Enough for a couple of seconds of a line that is not moving. Past that the useful thing
         * is not to remember more, it is to tell the caller that nothing is going out.
         */
        const val ROOM = 16
        const val LOOK_EVERY_MILLIS = 200L
    }
}
