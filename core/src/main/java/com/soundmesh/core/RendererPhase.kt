package com.soundmesh.core

import kotlin.math.abs

/**
 * SyncRenderer's drift-sampling phase.
 *
 * PlaybackScheduler.poll only releases a chunk once its instant has arrived, so a device's own
 * release cadence leaves a startup release-phase error of up to one whole chunk period (~20ms) -
 * three orders of magnitude bigger than the ~20ppm steady-state crystal drift DriftController's
 * 1Hz, one-frame correction budget is sized for. ACQUIRING samples and corrects on every chunk to
 * work that startup error off quickly; TRACKING then holds the spec's slower 1Hz cadence, which is
 * all steady-state drift needs.
 *
 * The startup error is not the only time that error appears: the renderer re-pins the timeline on
 * every played chunk, so any starvation gap re-draws it mid-run. TRACKING therefore falls back to
 * ACQUIRING on a wide enough excursion - see [nextPhaseState].
 */
enum class RendererPhase { ACQUIRING, TRACKING }

/**
 * [consecutiveInDeadband] only counts while ACQUIRING, and drives convergence into TRACKING;
 * [consecutiveBeyondThreshold] only counts while TRACKING, and drives the fallback back to
 * ACQUIRING. The two are deliberately separate counters: they answer unrelated questions and are
 * tuned apart.
 */
data class PhaseState(
    val phase: RendererPhase,
    val consecutiveInDeadband: Int,
    val consecutiveBeyondThreshold: Int
) {
    companion object {
        val INITIAL = PhaseState(RendererPhase.ACQUIRING, 0, 0)
    }
}

/** Matches DriftController.MEDIAN_WINDOW: convergence means the whole median window agrees. */
const val REQUIRED_CONSECUTIVE_IN_DEADBAND = 5

/**
 * How far the filtered error must be from zero for TRACKING to call it a slip rather than noise.
 *
 * 192 frames is 4ms at 48kHz, four times DriftController's 48-frame deadband. Measured drift-sample
 * noise is about 2.5 frames rms and 8 frames peak, so this sits ~24x above the peak noise and
 * cannot be tripped by it. It is also well under one chunk (960 frames), so it still catches slips
 * smaller than a whole chunk period - half a chunk is 480 frames, already far past the 240-frame
 * chirp alignment budget.
 */
const val REACQUIRE_THRESHOLD_FRAMES = 192

/**
 * How many samples in a row must sit at or beyond [REACQUIRE_THRESHOLD_FRAMES] before TRACKING
 * gives up and reacquires. One filtered sample is not a diagnosis; at TRACKING's 1Hz cadence two
 * samples is only two seconds of detection latency, which is nothing against the ~19s ACQUIRING
 * then needs to work a one-chunk error off.
 */
const val REQUIRED_CONSECUTIVE_BEYOND_THRESHOLD = 2

/**
 * Advances the ACQUIRING/TRACKING state machine by one drift sample.
 *
 * ACQUIRING switches to TRACKING once [requiredConsecutive] samples in a row have landed inside
 * DriftController's deadband - one lucky reading is not convergence, so this asks for a run as
 * long as the median filter's own window, meaning the whole window agrees rather than just its
 * median.
 *
 * TRACKING is not sticky. It used to be, on the reasoning that a later excursion outside the
 * deadband is ordinary steady-state noise the 1Hz loop is already built to correct. Twenty-eight
 * two-handset runs falsified that: four voided with a filtered error of -903 to -943 frames
 * against a 960-frame chunk while the phase read TRACKING. SyncRenderer re-pins the timeline to
 * every Play's own instant, and PlaybackScheduler.poll only releases a chunk once its instant has
 * arrived, so each re-pin after a starvation gap re-draws a release-phase error of up to a whole
 * chunk. At TRACKING's 1Hz cadence and one frame of correction per sample, clearing 960 frames
 * takes about sixteen minutes; the runs are ninety seconds, so the loop could never recover and
 * the chirp went out ~19ms out of alignment. ACQUIRING's per-chunk cadence clears the same error
 * in about nineteen seconds.
 *
 * So TRACKING falls back to ACQUIRING once [requiredConsecutiveBeyondThreshold] samples in a row
 * have landed at or beyond [reacquireThresholdFrames] (see [REACQUIRE_THRESHOLD_FRAMES] for why
 * that magnitude cannot be reached by noise). The fallback clears the in-deadband streak, so
 * getting back to TRACKING costs a fresh full run of [requiredConsecutive] samples rather than a
 * single lucky one.
 *
 * [inDeadband] and [filteredErrorFrames] are both taken because they answer different questions:
 * the deadband is DriftController's own, and is configurable there, so it is passed in already
 * decided rather than re-derived from a magnitude here.
 */
fun nextPhaseState(
    current: PhaseState,
    inDeadband: Boolean,
    filteredErrorFrames: Int,
    requiredConsecutive: Int = REQUIRED_CONSECUTIVE_IN_DEADBAND,
    reacquireThresholdFrames: Int = REACQUIRE_THRESHOLD_FRAMES,
    requiredConsecutiveBeyondThreshold: Int = REQUIRED_CONSECUTIVE_BEYOND_THRESHOLD
): PhaseState {
    if (current.phase == RendererPhase.TRACKING) {
        val beyond = if (abs(filteredErrorFrames) >= reacquireThresholdFrames) {
            current.consecutiveBeyondThreshold + 1
        } else {
            0
        }
        // Both streaks cleared: re-entry to TRACKING must earn a fresh full in-deadband run.
        if (beyond >= requiredConsecutiveBeyondThreshold) return PhaseState(RendererPhase.ACQUIRING, 0, 0)
        return current.copy(consecutiveBeyondThreshold = beyond)
    }
    val consecutive = if (inDeadband) current.consecutiveInDeadband + 1 else 0
    val phase = if (consecutive >= requiredConsecutive) RendererPhase.TRACKING else RendererPhase.ACQUIRING
    return PhaseState(phase, consecutive, 0)
}

/**
 * How long to wait between drift samples in [phase].
 *
 * ACQUIRING samples once per chunk ([chunkNanos], ~50Hz) so the startup release-phase error is
 * worked off in seconds; TRACKING drops to [trackingNanos], the 1Hz cadence sections 9.1 and 12
 * of the design call for, which is all steady-state crystal drift needs. The direction is the
 * point: at one frame of correction per sample, sampling a one-chunk error at 1Hz takes about
 * sixteen minutes, so an inverted mapping silently reinstates exactly what the split removes.
 *
 * Lives here rather than in the renderer so it can be pinned by a test - the renderer itself sits
 * behind android.media and has no JVM seam.
 */
fun driftIntervalNanos(phase: RendererPhase, chunkNanos: Long, trackingNanos: Long): Long =
    if (phase == RendererPhase.ACQUIRING) chunkNanos else trackingNanos
