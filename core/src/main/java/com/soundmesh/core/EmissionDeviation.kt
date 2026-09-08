package com.soundmesh.core

/**
 * One handset's own emission, chirp by chirp, against the schedule it was given.
 *
 * A calibration hands both handsets the same evenly spaced instants, so where a chirp actually
 * landed in a recording, minus where the schedule said it should, is that handset's emission
 * jitter - and it is a per-handset quantity, unlike everything else a run produces.
 * [AlignmentAnalysis.combineFacing] can only ever answer with the difference between the two:
 * `alignmentError = C + host emission - sink emission`, so a run's scatter never says which side
 * moved. These do.
 *
 * **Which index is whose is decided by [CalibrationSchedule], not by the field names.** The sink
 * plays at the plan's instant and the host half a second later, and `first`/`second` are ordered by
 * arrival, so in every recording `firstIndex` is the sink's chirp and `secondIndex` is the host's.
 * Reading it the other way round on 2026-09-08 filed a handset's whole emission behaviour against
 * the other handset, and the mistake survived a day because both readings are equally plausible
 * looking numbers. Section 21 of docs/feasibility-results/on-device-calibration.md.
 *
 * Both recordings hold both chirps, so each emission is read twice, independently. That is the
 * check the analysis was missing: a real emission event is seen by both microphones, and the two
 * readings of one across 290 archived chirps differ by a sd of 10.4 frames, while the steps being
 * looked for are 56.
 *
 * What this is **not** for: correcting the alignment error the verdict is judged on. Removing a
 * handset's emission jitter from the combined value cuts a run's scatter from 0.704 ms to 0.292
 * and moves the eighteen-run spread of cluster means from 0.488 to 0.460 - almost nothing, because
 * five chirps already average the jitter out, and what is left between runs is a different term.
 * It also shifts the constant by 0.17 ms, which would be wrong to apply: those steps are real
 * sound leaving a real speaker, and the correction a pair carries has to include them.
 */
object EmissionDeviation {
    /**
     * How far each chirp landed from an evenly spaced grid, in milliseconds, centred on the run.
     *
     * Centred on the median rather than on the first chirp: the first has no claim to being the
     * unstepped one, and a whole-run offset is not recoverable from inside a run either way - it
     * is indistinguishable from the constant the run is measuring. What survives centring is the
     * shape, which is the part that says whether one chirp stepped.
     *
     * A chirp that could not be read is null and takes no part in the centre.
     */
    fun of(indices: List<Int?>, intervalFrames: Int, sampleRate: Int = ChirpGenerator.SAMPLE_RATE): List<Double?> {
        require(intervalFrames > 0) { "chirps sharing an instant have no grid to be measured against" }
        require(sampleRate > 0) { "a recording with no sample rate has no time in it" }
        val offGrid = indices.mapIndexed { repeat, index -> index?.minus(repeat.toLong() * intervalFrames) }
        val readable = offGrid.filterNotNull()
        if (readable.isEmpty()) return indices.map { null }
        val centre = median(readable)
        return offGrid.map { it?.let { off -> (off - centre) / sampleRate * 1000 } }
    }

    /**
     * The spread of [deviations], or null when fewer than two chirps were readable.
     *
     * The plain sd, matching what [AlignmentVerdict] reports about a run, so the two can be put
     * side by side: a run whose combined scatter is large and whose two emission spreads are small
     * did not get that scatter from a speaker.
     */
    fun spreadMs(deviations: List<Double?>): Double? {
        val readable = deviations.filterNotNull()
        if (readable.size < 2) return null
        val mean = readable.average()
        return kotlin.math.sqrt(readable.sumOf { (it - mean) * (it - mean) } / (readable.size - 1))
    }

    /** The midpoint, averaging the middle two of an even count, as [AlignmentVerdict] does. */
    private fun median(values: List<Long>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle].toDouble()
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
