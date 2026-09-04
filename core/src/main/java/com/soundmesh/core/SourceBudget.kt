package com.soundmesh.core

/**
 * How long a song a handset can hold.
 *
 * A whole song is decoded into memory before a note of it is played, so that reading a chunk stays
 * arithmetic over an array and no decoder call ever lands on the thread that feeds the output.
 * That choice is what puts a ceiling on the length, and the ceiling is memory rather than taste:
 * both handsets here allow an app 384 MB, and while it is converted a 44.1 kHz song occupies the
 * decoded source, one channel of it as floats, and the converted result at once - about
 * 0.545 MB for every second of it.
 *
 * Seven minutes is that budget with room left for the rest of the app. It is deliberately measured
 * against the worst case: a song already at 48 kHz needs no conversion and costs 0.192 MB a second,
 * so one of those is refused at seven minutes when it would have fitted at twenty. The alternative
 * is a limit that depends on a property nobody chose their music by.
 */
object SourceBudget {
    /** Seven minutes. Most songs are three to five. */
    const val MAX_WHOLE_SECONDS = 420

    /**
     * Whether a song of this length has to be refused rather than played.
     *
     * [durationMicros] is MediaFormat's own unit. A container that declares no length at all gets
     * no refusal: what it gets instead is the decode stopping at the limit, which is the same
     * bounded read that has always been there.
     */
    fun tooLong(durationMicros: Long?): Boolean =
        durationMicros != null && durationMicros > MAX_WHOLE_SECONDS * 1_000_000L
}
