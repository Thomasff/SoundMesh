package com.soundmesh.core

/**
 * How long a song a handset can hold.
 *
 * A whole song is decoded into memory before a note of it is played, so that reading a chunk stays
 * arithmetic over an array and no decoder call ever lands on the thread that feeds the output.
 * That choice is what puts a ceiling on the length, and the ceiling is memory rather than taste.
 * Both handsets here allow an app 384 MB. A 6:50 song at 44.1 kHz was measured on one of them
 * mid-conversion at **291 MB live in a 315 MB heap**, which is 0.71 MB for every second of it -
 * arithmetic over what is simultaneously held predicted 0.545, and the difference is the decode
 * buffer still waiting to be collected while its copy is already made.
 *
 * Six minutes is that measured slope with room to spare: about 276 MB, or 72% of the limit, at a
 * moment that still has to allocate an 80 MB array. Seven left four megabytes.
 *
 * The limit is deliberately the worst case: a song already at 48 kHz needs no conversion and costs
 * far less, so one of those is refused at six minutes when it would have fitted at twenty. The
 * alternative is a limit that depends on a property nobody chose their music by.
 */
object SourceBudget {
    /** Six minutes. Most songs are three to five. */
    const val MAX_WHOLE_SECONDS = 360

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
