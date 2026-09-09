package com.soundmesh.core

/**
 * How long a song a handset can hold.
 *
 * A whole song is decoded into memory before a note of it is played, so that reading a chunk stays
 * arithmetic over an array and no decoder call ever lands on the thread that feeds the output.
 * That choice is what puts a ceiling on the length, and the ceiling is memory rather than taste.
 * Both handsets here allow an app 384 MB. A 6:50 song at 44.1 kHz was measured on one of them
 * mid-conversion at **291 MB live in a 315 MB heap**, which is 0.71 MB for every second of it.
 *
 * The ceiling is a peak in bytes, not a length: what fills it is the sample rate, and asking the
 * question in seconds is what used to make one answer serve every recording. A 44.1 kHz song is
 * held three times over at its worst moment - the decode buffer, the copy taken out of it, and the
 * converted result - plus a channel unpacked to float. A song already at 48 kHz stereo is handed
 * straight through by [Resampler] as the same array, so two of those four terms are never
 * allocated and it costs less than half as much a second. Charging both of them six minutes
 * refused a 48 kHz song at six minutes that this arithmetic says is safe at eleven.
 */
object SourceBudget {
    /**
     * The peak this is willing to reach, fixed at what the measured song was allowed.
     *
     * Written as the anchor rather than as a round number of megabytes on purpose: the only
     * length here with a measurement under it is six minutes of 44.1 kHz stereo, and defining the
     * budget as that product means that length cannot move. Every other rate is that same
     * measurement divided by a ratio of [peakBytesPerSecond] against itself, so the model is only
     * ever asked which formats are dearer than the measured one and by how much - never how many
     * megabytes anything costs outright.
     */
    private val PEAK_BUDGET_BYTES: Long = MEASURED_SECONDS * peakBytesPerSecond(MEASURED_RATE, 2)

    /**
     * The longest song of this format that fits, in whole seconds.
     *
     * [channels] above the renderer's two is priced as two rather than honestly. A surround mix
     * is refused by a message that names its channels, and pricing its six would refuse an
     * ordinary four minute one as too long first - sending whoever read that off to look for a
     * shorter copy of a file whose length was never the problem.
     */
    fun maxSeconds(sampleRate: Int, channels: Int): Int {
        require(sampleRate > 0) { "a sample rate is needed to say how long a song may be" }
        return (PEAK_BUDGET_BYTES / peakBytesPerSecond(sampleRate, channels)).toInt()
    }

    /**
     * Whether a song of this length and format has to be refused rather than played.
     *
     * [durationMicros] is MediaFormat's own unit. A container that declares no length at all gets
     * no refusal: what it gets instead is the decode stopping at [maxSeconds], which is the same
     * bounded read that has always been there.
     */
    fun tooLong(durationMicros: Long?, sampleRate: Int, channels: Int): Boolean =
        durationMicros != null && durationMicros > maxSeconds(sampleRate, channels) * 1_000_000L

    /**
     * What one second of this format costs at the worst moment of loading it, in bytes.
     *
     * The four terms are the four arrays that are live at once while the conversion runs, and
     * they are the reason the answer is not proportional to the sample rate alone:
     *
     * - the decode buffer, reserved in the renderer's format because the source's is not known
     *   until the decoder answers, so it is the same size whatever the song turns out to be;
     * - the copy taken out of it, which is the only term that is the source's own size;
     * - the converted result - not allocated at all when the source already is the renderer's
     *   format, because [Resampler] returns that array unchanged;
     * - one channel unpacked to float, held one at a time, and only when the rate has to change.
     *
     * At 44.1 kHz stereo this comes to 0.737 MB a second against the 0.71 that was measured, so
     * the model runs about four percent dear - which is the direction to be wrong in. Mono at the
     * renderer's rate is the one shape that costs *more* than its stereo neighbour: it is half
     * the samples, but sharing them across two channels writes a second array that 48 kHz stereo
     * never writes.
     */
    private fun peakBytesPerSecond(sampleRate: Int, channels: Int): Long =
        // A container that will not say how many channels it has is charged whichever shape is
        // dearer at its rate, which at the renderer's own rate is the narrower one.
        if (channels < 1) maxOf(cost(sampleRate, 1), cost(sampleRate, RENDERER_CHANNELS))
        else cost(sampleRate, minOf(channels, RENDERER_CHANNELS))

    private fun cost(sampleRate: Int, channels: Int): Long {
        val converts = sampleRate != RENDERER_RATE || channels != RENDERER_CHANNELS
        val resamples = sampleRate != RENDERER_RATE
        return RENDERER_BYTES_PER_SECOND.toLong() +
            sampleRate.toLong() * channels * BYTES_PER_SAMPLE +
            (if (converts) RENDERER_BYTES_PER_SECOND.toLong() else 0L) +
            (if (resamples) sampleRate.toLong() * BYTES_PER_FLOAT else 0L)
    }

    /** The song the ceiling was measured on, and the length it was measured as allowing. */
    private const val MEASURED_RATE = 44_100
    private const val MEASURED_SECONDS = 360L

    private const val RENDERER_RATE = 48_000
    private const val RENDERER_CHANNELS = 2
    private const val BYTES_PER_SAMPLE = 2
    private const val BYTES_PER_FLOAT = 4
    private const val RENDERER_BYTES_PER_SECOND = RENDERER_RATE * RENDERER_CHANNELS * BYTES_PER_SAMPLE
}
