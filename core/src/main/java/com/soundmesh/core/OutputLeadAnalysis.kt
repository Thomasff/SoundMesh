package com.soundmesh.core

/**
 * One handset's reading of how far one of its output paths runs ahead of another.
 *
 * [leadMicros] is positive when the subject output is heard earlier than the reference one, which
 * is the direction a renderer corrects by holding its own clock back.
 */
data class OutputLeadReading(
    val referenceIndex: Int?,
    val subjectIndex: Int?,
    val leadMicros: Long?,
    val confidence: AlignmentConfidence,
    val ratios: List<Double?>,
    val atSearchEdge: List<Boolean?>
)

/**
 * What a whole calibration decided, and why it decided nothing when it did.
 */
data class OutputLeadResult(
    val leadMicros: Long?,
    val usedReadings: Int,
    val spreadMicros: Long?,
    val refusal: String?
)

/**
 * Measures one handset's own output paths against each other, out of a single recording it made
 * of itself.
 *
 * This is the whole of the asymmetry with [AlignmentAnalysis]: that one compares two handsets and
 * has to remove the room between them, this one compares two outputs of the same handset. One
 * speaker, one microphone, one clock, and both chirps travelling the same few centimetres of air
 * through the same capture chain - so the flight time, the input latency and the instant the
 * recording opened are all common to the two arrivals and cancel in the difference exactly, rather
 * than being estimated away. Nothing here needs a second device, a network, a clock sync or a
 * tape measure, which is why a handset can run it on its own.
 *
 * What it cannot do is measure an output against the truth. It measures one output against
 * another, and calls the reference one zero. That is the quantity the product wants: a host
 * playing on an unusual output has to sound like a host playing on the ordinary one.
 */
object OutputLeadAnalysis {
    /**
     * Where each chirp landed, and how far apart the two outputs were.
     *
     * Both chirps are looked for in their own window around where they were scheduled, so neither
     * has to be told apart from the other by which correlates louder - the schedule already
     * separates them. That matters here because the subject output is routinely the quieter of the
     * two: its volume is a different slider, and on both measured handsets it sits lower.
     */
    fun read(
        recorded: ShortArray,
        reference: ShortArray,
        recordingStartedAtHostNanos: Long,
        referenceChirpAtHostNanos: Long,
        subjectChirpAtHostNanos: Long,
        uncertaintyFrames: Int = CalibrationWindow.DEFAULT_UNCERTAINTY_FRAMES,
        sampleRate: Int = ChirpGenerator.SAMPLE_RATE
    ): OutputLeadReading {
        val referenceArrival = arrival(recorded, reference, recordingStartedAtHostNanos, referenceChirpAtHostNanos, uncertaintyFrames, sampleRate)
        val subjectArrival = arrival(recorded, reference, recordingStartedAtHostNanos, subjectChirpAtHostNanos, uncertaintyFrames, sampleRate)
        val trustworthy = trustworthy(referenceArrival) && trustworthy(subjectArrival)
        // Both delays are measured from the instant each chirp was scheduled for, so whatever the
        // capture chain and the recording's own start add is in both of them and leaves here.
        val leadFrames = if (!trustworthy) null else {
            (referenceArrival!!.index - scheduledFrame(recordingStartedAtHostNanos, referenceChirpAtHostNanos, sampleRate)) -
                (subjectArrival!!.index - scheduledFrame(recordingStartedAtHostNanos, subjectChirpAtHostNanos, sampleRate))
        }
        return OutputLeadReading(
            referenceIndex = referenceArrival?.index,
            subjectIndex = subjectArrival?.index,
            leadMicros = leadFrames?.let { it.toLong() * 1_000_000L / sampleRate },
            confidence = if (trustworthy) AlignmentConfidence.OK else AlignmentConfidence.UNRELIABLE,
            ratios = listOf(referenceArrival?.ratio, subjectArrival?.ratio),
            atSearchEdge = listOf(referenceArrival?.atSearchEdge, subjectArrival?.atSearchEdge)
        )
    }

    /**
     * The one number to store, out of every repeat the calibration played.
     *
     * The median rather than the mean, and a refusal rather than a best effort. One handset's
     * emission moves in steps of tens of frames, so repeats disagree by a millisecond or so even
     * when everything is working; readings that disagree by much more than that mean something
     * else happened - a notification over the top of a chirp, a hand moved across the speaker -
     * and a constant averaged out of those is worse than no constant at all, because the product
     * applies it silently and nobody ever looks at it again.
     */
    fun combine(
        readings: List<OutputLeadReading>,
        minimumReadings: Int,
        maximumSpreadMicros: Long
    ): OutputLeadResult {
        val measured = readings.mapNotNull { it.leadMicros }.sorted()
        if (measured.size < minimumReadings) {
            return OutputLeadResult(null, measured.size, null, "only ${measured.size} of ${readings.size} chirp pairs were heard clearly, and $minimumReadings are needed")
        }
        val spread = measured.last() - measured.first()
        if (spread > maximumSpreadMicros) {
            return OutputLeadResult(null, measured.size, spread, "the repeats disagree by $spread us, over the $maximumSpreadMicros us this will accept")
        }
        return OutputLeadResult(measured[measured.size / 2], measured.size, spread, null)
    }

    private fun arrival(
        recorded: ShortArray,
        reference: ShortArray,
        recordingStartedAtHostNanos: Long,
        chirpAtHostNanos: Long,
        uncertaintyFrames: Int,
        sampleRate: Int
    ): ChirpArrival? {
        val window = CalibrationWindow.searchWindow(
            recordingStartedAtHostNanos = recordingStartedAtHostNanos,
            fromHostNanos = chirpAtHostNanos,
            toHostNanos = chirpAtHostNanos,
            uncertaintyFrames = uncertaintyFrames,
            sampleRate = sampleRate
        )
        return ChirpCorrelator.findArrival(recorded, reference, window.first, window.last)
    }

    private fun trustworthy(arrival: ChirpArrival?): Boolean =
        arrival != null && arrival.ratio >= ChirpCorrelator.MIN_TRUSTWORTHY_RATIO && !arrival.atSearchEdge

    private fun scheduledFrame(recordingStartedAtHostNanos: Long, atHostNanos: Long, sampleRate: Int): Int =
        ((atHostNanos - recordingStartedAtHostNanos) * sampleRate / 1_000_000_000L).toInt()
}
