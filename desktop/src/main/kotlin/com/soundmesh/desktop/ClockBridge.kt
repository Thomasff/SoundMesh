package com.soundmesh.desktop

import java.lang.foreign.Arena
import kotlin.math.abs

/**
 * Are the two clocks this client has to speak at once the same clock?
 *
 * Everything already measured on Windows is on QPC: the renderer schedules on it and the capture
 * stream stamps with it. Everything the handsets say on the wire is on `System.nanoTime()` - the
 * clock exchange carries four of its readings and nothing else. For the two machines to be
 * measured against each other, a QPC tick has to be turned into a nanoTime nanosecond, and there
 * are two very different worlds that could be in:
 *
 *  - **One counter, two units.** Then the conversion is a fixed offset and a fixed scale, both
 *    readable once, and the network half and the audio half share a timebase for free - which is
 *    the arrangement the handsets already enjoy, where both halves read the same monotonic clock.
 *
 *  - **Two counters.** Then there is a second unknown offset between them, with its own drift, and
 *    it sits inside every cross-machine number without appearing in any of them.
 *
 * This is checkable to a perfect criterion, and the criterion is a difference: read the two back to
 * back, convert, subtract. If they are one counter the difference is a constant - not a small
 * number, a *constant* - and its scatter is only how long the two reads took. If they are two, the
 * difference walks, and how fast it walks is the thing that would have been silently wrong.
 *
 *   java --enable-native-access=ALL-UNNAMED -cp "<lib>" com.soundmesh.desktop.ClockBridgeKt
 */
fun main() {
    Arena.ofConfined().use { arena ->
        val scratch = arena.allocate(8, 8)
        val qpf = Wasapi.qpcFrequency(scratch)
        println("QPC frequency: $qpf Hz")
        println("nanoTime     : ${1_000_000_000L} Hz by definition")
        println()

        // Sandwiched: nanoTime, QPC, nanoTime. The QPC read happened somewhere inside the two
        // nanoTime reads, so the difference is known to within how long that sandwich took, and
        // that width is printed rather than assumed to be negligible.
        val chunks = 60
        val perChunk = 200
        val samples = chunks * perChunk
        val differences = LongArray(samples)
        val widths = LongArray(samples)
        val mids = LongArray(samples)
        for (i in 0 until samples) {
            val before = System.nanoTime()
            val qpc = Wasapi.qpc(scratch)
            val after = System.nanoTime()
            val qpcNanos = Math.round(qpc.toDouble() / qpf * 1e9)
            val midpoint = (before + after) / 2
            mids[i] = midpoint
            differences[i] = midpoint - qpcNanos
            widths[i] = after - before
            // Spread over a few seconds rather than taken in a burst, because two counters that
            // drift apart at a handful of parts per million would look identical to one counter
            // over a millisecond.
            if (i % perChunk == perChunk - 1) Thread.sleep(200)
        }

        // One number per chunk, and it is the *narrowest* read in that chunk rather than the
        // median. Being interrupted between two reads can only make a read look wider and its
        // midpoint later; nothing makes one look narrower than it was. So the minimum is the
        // reading least contaminated by the scheduler, and a statistic that averages instead
        // carries the scheduler's tail into the answer - which is how the first version of this
        // program came back "two counters" off a 545 us outlier.
        val marks = ArrayList<Pair<Double, Long>>(chunks)
        for (c in 0 until chunks) {
            var best = c * perChunk
            for (i in c * perChunk until (c + 1) * perChunk) if (widths[i] < widths[best]) best = i
            marks.add(mids[best].toDouble() to differences[best])
        }
        val t0 = marks.first().first
        val elapsed = marks.map { (it.first - t0) / 1e9 }
        val values = marks.map { it.second.toDouble() }

        val n = chunks.toDouble()
        val mx = elapsed.average()
        val my = values.average()
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until chunks) {
            val dx = elapsed[i] - mx
            sxx += dx * dx
            sxy += dx * (values[i] - my)
        }
        val slopeNanosPerSecond = sxy / sxx
        val span = elapsed.last()
        val scatter = (0 until chunks)
            .map { abs(values[it] - (my + slopeNanosPerSecond * (elapsed[it] - mx))) }
            .max()

        println("difference (nanoTime - QPC), one narrowest read per chunk:")
        println("  $chunks marks over ${"%.1f".format(span)} s, n = $n readings")
        println("  read width : median ${widths.sorted()[samples / 2]} ns, narrowest ${widths.min()} ns")
        println("  spread     : ${(values.max() - values.min()).toLong()} ns end to end")
        // The constant itself, not only its steadiness. Zero would mean nanoTime is QPC scaled
        // and nothing else, so any process on this machine converts without calibrating; anything
        // else is a per-JVM origin that has to be read once and carried.
        println("  constant   : ${values.sorted()[chunks / 2].toLong()} ns (nanoTime - QPC in ns)")
        println("  slope      : ${"%+.1f".format(slopeNanosPerSecond)} ns/s " +
            "(${"%+.3f".format(slopeNanosPerSecond / 1000.0)} ppm), scatter about it ${scatter.toLong()} ns")
        println()
        // A slope is the only thing that distinguishes the two cases: one counter read twice has
        // no way to drift, however noisy each read is. The threshold is the scatter itself - a
        // slope smaller than the noise it is fitted through has not been measured.
        val slopeOverSpan = abs(slopeNanosPerSecond) * span
        println(
            if (slopeOverSpan <= scatter) {
                "one counter: over ${"%.0f".format(span)} s the fitted slope adds up to " +
                    "${slopeOverSpan.toLong()} ns, inside its own scatter of ${scatter.toLong()} ns. " +
                    "A QPC tick converts to a nanoTime nanosecond by scale alone."
            } else {
                "two counters: they drift apart by ${"%+.3f".format(slopeNanosPerSecond / 1000.0)} ppm, " +
                    "which is a second unknown that every cross-machine number would carry."
            }
        )
    }
}
