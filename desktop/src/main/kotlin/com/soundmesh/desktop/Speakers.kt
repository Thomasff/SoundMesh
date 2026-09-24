package com.soundmesh.desktop

import java.lang.foreign.Arena

/**
 * This machine's output, started, and what to do to let go of it.
 *
 * A pair rather than the renderer itself so the sessions can be tested without a sound card: a
 * test hands them a straight line and checks it was closed.
 */
class Speakers(
    val output: FrameOutput,
    val deviceName: String?,
    private val level: () -> Float = { 0f },
    private val closer: () -> Unit
) : AutoCloseable {

    override fun close() = closer()

    /** How loud what is sounding here right now is, 0..1 - see [WasapiRenderer.loudness]. */
    fun loudness(): Float = level()

    companion object {
        /**
         * The default endpoint, running, in the order the command-line tools open it.
         *
         * Never on the window's thread. The renderer initialises COM on whatever thread builds it,
         * multithreaded, and throws if that thread already chose otherwise - and whether the toolkit
         * thread has is not something anybody here has checked. start() also waits, up to two
         * seconds, for the stream to really move. Both are reasons for a session's own thread.
         */
        fun open(): Speakers {
            val renderer = WasapiRenderer()
            try {
                val clock = Arena.ofConfined().use { DesktopClock.measure(it) }
                // Refuses a device that is not at the stream's rate, and says how to fix it.
                val output = WasapiOutput(renderer, clock)
                renderer.start()
                return Speakers(output, renderer.deviceName, { renderer.loudness }) { renderer.close() }
            } catch (e: Throwable) {
                renderer.close()
                throw e
            }
        }
    }
}
