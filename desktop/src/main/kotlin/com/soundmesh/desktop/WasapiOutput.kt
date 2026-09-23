package com.soundmesh.desktop

import com.soundmesh.core.TonePcmSource

/**
 * The default output device seen as a frame timeline in this machine's own nanoseconds.
 *
 * Two conversions, and they are the only two: [DesktopClock] turns a `System.nanoTime()` instant -
 * the clock the wire speaks - into a QPC tick, and the renderer turns a QPC tick into the frame
 * the engine will be consuming then. A fresh reading of the device clock is taken per call rather
 * than held, which is what keeps this side free of anything that can accumulate.
 *
 * What this does not do is say when the air moved. The renderer's own comment makes the point at
 * length: the distance between "the engine consumed this frame" and "a room heard it" is this
 * machine's output delay constant, and it takes a microphone to measure. Until somebody does, a
 * sink built on this plays in step with the host's clock and at an unknown constant from the
 * handsets, and the honest thing is to say so rather than to report a number.
 */
class WasapiOutput(
    private val renderer: WasapiRenderer,
    private val clock: DesktopClock
) : FrameOutput {

    init {
        // Nothing here resamples, so a device running at anything else would play the stream at
        // the wrong speed - audibly, but only if somebody happened to know what it should sound
        // like. Refusing names the fix, which is one switch in the Windows sound settings.
        require(renderer.format.sampleRate == TonePcmSource.SAMPLE_RATE) {
            "this endpoint runs at ${renderer.format.sampleRate} Hz and the stream is " +
                "${TonePcmSource.SAMPLE_RATE} Hz; set the device to ${TonePcmSource.SAMPLE_RATE} Hz " +
                "in Sound settings, or teach this class to resample"
        }
    }

    override fun frameAtLocalNanos(localNanos: Long): Long =
        renderer.frameAt(clock.qpcAt(localNanos), renderer.sampleClock())

    override fun schedule(samples: ShortArray, channels: Int, atFrame: Long): Boolean =
        renderer.scheduleIfAhead(samples, channels, atFrame)

    override fun dropScheduled() {
        renderer.dropAhead()
    }
}
