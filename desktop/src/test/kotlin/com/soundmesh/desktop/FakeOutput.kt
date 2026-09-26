package com.soundmesh.desktop

/**
 * A frame timeline that is a straight line through the origin.
 *
 * That is what makes a sign error in the clock offset visible: frame zero is local nanosecond
 * zero, so a chunk's landing frame is a number somebody can work out by hand. Shared by the
 * playout tests and the host's, because the host's local half is the same playout with the
 * offset fixed at zero and the point of its test is that the two halves get the same instants.
 */
internal class FakeOutput(private val sampleRate: Int = 48000) : FrameOutput {
    var firstSchedulableFrame: Long = 0
    val scheduled = ArrayList<Triple<Long, Int, ShortArray>>()
    var drops = 0

    override fun dropScheduled() {
        drops++
    }

    // Whole seconds and the remainder apart: nanoTime here counts from boot, and the plain product
    // overflows a Long once a machine has been up 53 hours, which failed every test through here.
    override fun frameAtLocalNanos(localNanos: Long): Long =
        localNanos / 1_000_000_000L * sampleRate + localNanos % 1_000_000_000L * sampleRate / 1_000_000_000L

    override fun schedule(samples: ShortArray, channels: Int, atFrame: Long): Boolean {
        if (atFrame < firstSchedulableFrame) return false
        scheduled.add(Triple(atFrame, channels, samples))
        return true
    }
}
