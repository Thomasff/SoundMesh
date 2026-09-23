package com.soundmesh.product

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.soundmesh.probe.R
import com.soundmesh.probe.sync.CalibrationAudioSource
import com.soundmesh.probe.sync.CalibrationRunner
import com.soundmesh.probe.sync.HandsetRoundSpeaker
import com.soundmesh.probe.sync.PairedHost
import com.soundmesh.probe.sync.EventLog
import com.soundmesh.probe.sync.RouterPoke
import com.soundmesh.probe.sync.holdingRadio
import com.soundmesh.probe.sync.keepingAwake
import com.soundmesh.probe.sync.radioHoldOf
import com.soundmesh.probe.sync.routerPokeOf
import com.soundmesh.session.SessionService

/**
 * How far off an instant on the host's clock is, on the scale a screen counts down in.
 *
 * File scope rather than on either caller: the activity and the service both hand this to their
 * own [SinkRoundReport], and two copies of a subtraction is two places for a sign to be wrong.
 */
internal fun whenItReaches(instantNanos: Long, now: () -> Long): Long =
    SystemClock.elapsedRealtime() + (instantNanos - now()) / 1_000_000L

/**
 * Runs [body] with this handset's radio held out of power save, saying whether it worked.
 *
 * Lifted out of [PeerCalibrateActivity.start] when the sink half stopped needing a screen. Both
 * roles need it and both need it recorded the same way - an access point buffers frames for a
 * station that is asleep, and the reply half of an exchange is as much of the round trip as the
 * request half, so a host dozing costs the sink exactly the same milliseconds.
 *
 * [held] is called with whether the lock was actually taken. On the record rather than assumed:
 * the lock is best effort, and a run whose lock quietly did nothing looks exactly like a run that
 * proves power save is irrelevant.
 */
internal fun withRadioAwake(
    context: Context,
    events: EventLog,
    held: (Boolean) -> Unit,
    body: () -> Unit
) {
    holdingRadio(radioHoldOf(context), held = held) {
        // The lock above is not enough on its own; what keeps a radio awake is having something to
        // receive. See keepingAwake for the three numbers that say so.
        val poke: RouterPoke? = routerPokeOf(context)
        // Said out loud because a poke that reaches nobody measures exactly like no poke at all:
        // the first version of this aimed at an unreachable gateway and a whole round went by
        // looking like evidence that power save does not matter.
        keepingAwake(poke, answered = { answered ->
            events.write(
                when {
                    poke == null || answered == null ->
                        "radio-awake: this handset named no IPv4 router, so nothing is keeping it awake"
                    answered -> "radio-awake: " + poke.router.hostAddress + " answered"
                    else -> "radio-awake: " + poke.router.hostAddress + " did not answer"
                }
            )
        }, body = body)
    }
}

/**
 * Stops whatever this handset is playing, because a round is about to record it.
 *
 * Not a courtesy, and not a sink's problem alone - every handset in a round records, the one that
 * gathered it included. A round measures when a chirp arrived by correlating against the chirp,
 * and a recording with music over the top of it still produces a number. So the cost of skipping
 * this is not a failed round: it is a confident wrong answer, applied to every session afterwards
 * with nothing anywhere to notice it by.
 *
 * Not restarted at the end. The rest of the room is still measuring, and a handset that started
 * playing on its own in the middle of their window would be the next thing over their microphones.
 * Somebody presses play when the room is done, which is where that decision belongs.
 */
internal fun hushWhateverIsPlaying(context: Context, events: EventLog) {
    if (SessionService.ACTIVE == null) return
    events.write("round-hush: stopping playback, a chirp measured through music measures the music")
    context.startService(
        Intent(context, SessionService::class.java).setAction(SessionService.ACTION_STOP)
    )
    // Bounded, and short against the sixteen seconds of clock a round opens with. A session that
    // will not let go is worth a round measured over it far less than it is worth saying so, and
    // the wait ending is not the same as the session having stopped.
    val until = SystemClock.elapsedRealtime() + HUSH_WAIT_MILLIS
    while (SessionService.ACTIVE != null && SystemClock.elapsedRealtime() < until) {
        runCatching { Thread.sleep(HUSH_POLL_MILLIS) }
    }
    if (SessionService.ACTIVE != null) {
        events.write("round-hush: the session was still up when the round began")
    }
}

/**
 * Stops the music because somebody has walked onto a screen that measures, rather than because a
 * round has started.
 *
 * The round already hushes, and that was late by exactly the part a person notices. A round opens
 * with sixteen seconds of clock before the first chirp, so the music used to keep playing through
 * the reading of the page, stop at the press, and then leave the room in silence with nothing
 * apparently happening. Arriving is the moment the decision is actually made - nobody opens this
 * screen to keep listening - so the stop happens then and the wait for it happens while the page
 * is being read.
 *
 * Off the main thread because the stop below waits for the session to let go. Cheap when there is
 * nothing playing: it returns before starting anything.
 */
internal fun hushOnArrival(context: Context, events: EventLog) {
    if (SessionService.ACTIVE == null) return
    Thread({ hushWhateverIsPlaying(context, events) }, "SoundMeshArrivalHush").start()
}

/** How long [hushWhateverIsPlaying] waits for playback to actually stop. */
private const val HUSH_WAIT_MILLIS = 3_000L

private const val HUSH_POLL_MILLIS = 50L

/**
 * A sink's round on this handset: core's one copy, handed this handset's audio and words.
 *
 * Every caller that used to build SinkRound with a Context builds it here, so the handset's
 * recorder, renderer, pairing and log are chosen in one place.
 */
internal fun handsetSinkRound(
    context: Context,
    request: SinkRoundRequest,
    radioHeld: () -> Boolean,
    calledOff: () -> Boolean,
    report: SinkRoundReport
): SinkRound = SinkRound(
    filesDir = context.filesDir,
    request = request,
    host = { PairedHost(context.filesDir).read()?.let { RoundHost(it.address, it.hostId) } },
    radioHeld = radioHeld,
    calledOff = calledOff,
    report = report,
    keepsRecording = keepsRecordings(context.filesDir),
    log = { Log.i(SINK_ROUND_LOG_TAG, it) },
    recorder = { runStore, caseId, hostNanosNow, source ->
        CalibrationRunner(runStore, caseId, CalibrationAudioSource.parse(source), hostNanosNow)
    },
    speaker = { offsetNanosNow, hostNanosNow -> HandsetRoundSpeaker(offsetNanosNow, hostNanosNow) }
)

/** A round's sentence in this handset's words, with the arguments its string always took. */
internal fun RoundLine.text(context: Context): String = when (this) {
    is RoundLine.Failed -> context.getString(R.string.pair_calibrate_failed, code)
    RoundLine.NoPairing -> context.getString(R.string.pair_calibrate_no_pairing)
    RoundLine.Clock -> context.getString(R.string.pair_calibrate_clock)
    is RoundLine.SlowLink -> context.getString(R.string.pair_calibrate_slow_link, medianMs, maxMs)
    RoundLine.CalledOff -> context.getString(R.string.pair_calibrate_room_called_off)
    RoundLine.Running -> context.getString(R.string.pair_calibrate_running)
    is RoundLine.RoomSinkDone ->
        context.getString(R.string.pair_calibrate_room_sink_done, handsets, readable)
    is RoundLine.RoomSinkApproximate ->
        context.getString(R.string.pair_calibrate_room_sink_approximate, handsets, readable, keptMs)
    is RoundLine.MeasuredOnly -> context.getString(R.string.pair_calibrate_measured_only, ms)
    is RoundLine.Kept -> context.getString(R.string.pair_calibrate_kept, reason)
    is RoundLine.Verified -> context.getString(R.string.pair_calibrate_verified, ms)
    is RoundLine.NotFolded -> context.getString(R.string.pair_calibrate_not_folded, ms)
    is RoundLine.Done -> context.getString(R.string.pair_calibrate_done, ms, observations)
}

/** An instant on System.nanoTime's scale, as a countdown on elapsedRealtime's. See [whenItReaches]. */
internal fun elapsedAtLocalNanos(localNanos: Long): Long = whenItReaches(localNanos) { System.nanoTime() }

/** What SinkRound logged under before it moved to core, kept so a logcat filter still finds it. */
private const val SINK_ROUND_LOG_TAG = "SoundMeshSinkRound"
