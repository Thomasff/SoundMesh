package com.soundmesh.product

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableDoubleState
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max

/**
 * What the edge is doing right now. One of four, and the one thing a frame loop is keyed on.
 *
 * [DARK] is the state this handset spends most of its life in, and the reason this is an enum at
 * all rather than a boolean and a float: a handset with no colour draws no edge, so there is
 * nothing for a frame loop to feed and the loop should not be running. See [rememberEdgeGlow].
 */
private enum class Glow { DARK, DISCONNECTED, PLAYING, STANDBY }

/**
 * How the waves carry themselves in one regime: how fast they travel, and how far they swing at
 * the quietest and the loudest this handset gets.
 *
 * Two of these exist. A handset that is connected but silent shows a slow, shallow ripple - it is
 * saying "here I am, this is my colour" and nothing more, and a big fast swing with no music behind
 * it is a promise the room is not keeping. When playback starts the waves speed up and the swing
 * opens out, and from then on the loudness moves it.
 *
 * Switching between them costs nothing to draw. The swing is one multiplier on a number the frame
 * already computes; it changes neither how many contours are stroked, nor how wide, which is the
 * only thing that predicts what a frame costs here (see [BadgeEdges]). A shallower swing is in fact
 * marginally *cheaper*, because a flatter wave is a shorter polyline.
 */
internal class WaveMood(val speed: Float, val swingQuiet: Float, val swingLoud: Float)

/**
 * The three things the screen edge is drawn from, as things to call once a frame from inside a
 * draw - **not** as values. See [rememberEdgeGlow] for why that distinction is the whole design.
 */
internal interface EdgeGlow {
    /** How lit the edge should be right now, 0..1. */
    fun amplitude(): Float

    /**
     * How far the waves have travelled, in seconds of the design's own nominal speed.
     *
     * An accumulator, not a stopwatch: each frame adds its own elapsed time times the current
     * [mood]'s speed. That is what lets the speed change without the waves jumping - a plain clock
     * multiplied by a speed moves every crest the instant the multiplier does, and switching from
     * the standing-by ripple to the playing wave would snap the whole ring sideways.
     *
     * Local, and never reset. It is deliberately not the shared playback position, and not shared
     * between handsets at all: phase carries no information, so two handsets showing different
     * crests are saying the same thing. What carries information is [amplitude], and that is each
     * handset's own loudness, which is already in step with every other handset's without anything
     * being sent.
     */
    fun travelSeconds(): Double

    /**
     * How much louder this instant is than the last few seconds have been, 0..1.
     *
     * Zero through anything held or steady, and near one on a hit. Only the lead wave's brightness
     * reads it - see [LEAD_PUNCH].
     */
    fun punch(): Float

    /** Which of the two characters the waves have right now. */
    fun mood(): WaveMood
}

/**
 * How bright this handset's screen edge should glow right now, 0..1 - as something to call once a
 * frame from inside a draw, **not** as a value.
 *
 * Three lit regimes, checked in order:
 * - **disconnected** - a sink whose standing line is down. Fixed and low: the breathing stops
 *   rather than keeps miming a room this handset is no longer part of.
 * - **playing** - read off [loudness] every frame, smoothed so the edge follows the music rather
 *   than flickering with every 20ms chunk.
 * - **standing by, not playing** - a slow, even breath, so a phone left in a corner still reads
 *   as alive from across the room.
 *
 * Deliberately not read off the screen's own state: that is polled five times a second on the
 * handset and twice on a computer, which is far too slow for something meant to move with the
 * music, and lifting that whole poll to a frame rate to carry one float would put the cost of this
 * decoration on a path that has to stay cheap for reasons that have nothing to do with it.
 * [loudness] reads the renderer's volatile directly instead - the handset's SyncRenderer.loudness,
 * the computer's WasapiRenderer.loudness.
 *
 * [disconnected] and [playing] are the caller's: on the handset a sink off its standing line and a
 * running session, on a computer the same two said by its own sessions.
 *
 * [everyNanos] is how long the loop sleeps between two frames that move the light. Zero, the
 * handset's, moves it on every frame the display draws. A computer passes more: its display may
 * draw 165 times a second, and on 09-24 the window with the light moving on every one of them cost
 * 40% of a core against 1.7% with it still - most of a core once the light went round the screen in
 * four windows. Sleeping, not skipping: a loop that asks for every frame and writes on one in five
 * measured 17% at 28 redraws a second, because asking for a frame is most of what one costs there.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * Why this hands back a function instead of a Float, and why [lit] exists
 * ────────────────────────────────────────────────────────────────────────────
 * Until 2026-09-20 this returned the Float, and HomeScreen read it. A value that changes every
 * frame, read during composition, recomposes whatever read it - here the whole of [HomeScreen],
 * sixty times a second, or a hundred and twenty on a panel that fast. Measured on the X10
 * (Android 10, 60Hz), parked on the role-picker with no colour and **no edge drawn at all**:
 *
 *     main thread 20.5% of a core, render thread 0.0%, frames rendered in 30s: 0
 *
 * Nothing was being drawn. All of it was composition being re-run to arrive at the same screen.
 * Deleting the call took the main thread to 2.3%, which is what attributes those eighteen points
 * to this file rather than to anything else on the screen.
 *
 * Two things fix it, and both are needed:
 * - the float is read inside the draw lambda (see `badgeEdge`), where a read invalidates the draw
 *   and not the composition;
 * - [lit] is false when there is no colour to draw, and then no frame loop runs at all. Without
 *   it a handset that draws no edge would still wake sixty times a second to move a number
 *   nothing reads, which is the same waste in a cheaper suit.
 */
@Composable
internal fun rememberEdgeGlow(
    lit: Boolean,
    disconnected: Boolean,
    playing: Boolean,
    everyNanos: Long = 0L,
    loudness: () -> Float,
): EdgeGlow {
    val heard by rememberUpdatedState(loudness)
    val glow = remember { mutableFloatStateOf(STANDBY_GLOW_MIN) }
    val punch = remember { mutableFloatStateOf(0f) }
    val travel = remember { mutableDoubleStateOf(0.0) }
    val mood: MutableState<WaveMood> = remember { mutableStateOf(QUIET_MOOD) }
    val regime = when {
        !lit -> Glow.DARK
        disconnected -> Glow.DISCONNECTED
        playing -> Glow.PLAYING
        else -> Glow.STANDBY
    }
    LaunchedEffect(regime) {
        mood.value = if (regime == Glow.PLAYING) PLAYING_MOOD else QUIET_MOOD
        // Nothing but playback has beats in it, and a punch left behind by the last song would sit
        // on the lead wave for as long as the handset stood by.
        punch.floatValue = 0f
        // The travel accumulator is deliberately not reset here. See [EdgeGlow.travelSeconds].
        when (regime) {
            // No loop: nothing on screen is reading this, and the frame the loop would ask for is
            // a frame the whole app would otherwise not have drawn.
            Glow.DARK -> Unit
            // No loop here either, so the waves hold still where they were - which is the same
            // statement the flat amplitude is making.
            Glow.DISCONNECTED -> glow.floatValue = DISCONNECTED_GLOW
            Glow.PLAYING -> followLoudness(glow, punch, travel, PLAYING_MOOD.speed, everyNanos) { heard() }
            Glow.STANDBY -> breathe(glow, travel, QUIET_MOOD.speed, everyNanos)
        }
    }
    return remember(glow, punch, travel, mood) {
        object : EdgeGlow {
            override fun amplitude(): Float = glow.floatValue
            override fun travelSeconds(): Double = travel.doubleValue
            override fun punch(): Float = punch.floatValue
            override fun mood(): WaveMood = mood.value
        }
    }
}

/**
 * Follows the renderer's own [loudness], two exponential steps per frame.
 *
 * ★ Both steps are time constants, not per-frame fractions. The first of them used to be
 * `smoothed += 0.25f * (target - smoothed)`, which is a different amount of smoothing on every
 * refresh rate the app runs at - the edge was twice as sluggish on the 120Hz handset as on the
 * 60Hz one, for no reason anybody chose. [LOUDNESS_TAU_SECONDS] is picked so that at 60Hz that
 * one is the old 0.25 to three decimal places.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * Why there are two of them, and why the answer is not the loudness any more
 * ────────────────────────────────────────────────────────────────────────────
 * Absolute loudness turns out to be the wrong question even after [heardAs] puts it on a scale the
 * ear uses. A held violin note or a voice under a mix is *steady* - it sits at one level for
 * seconds at a time - so an edge driven by the level holds just as still, and the effect reads as
 * decoration that happens to be on while music happens to be playing. What a person sees as "it is
 * moving with the music" is not the level. It is the **departure from the level**.
 *
 * So there are three running figures rather than one. [fast] is where the music is right now.
 * [slow] is where it has been sitting for the last few seconds. [spread] is how far from [slow] it
 * usually strays over that same window - how much this record moves at all.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * And why the departure is measured in the record's own units
 * ────────────────────────────────────────────────────────────────────────────
 * A first cut multiplied the departure by a fixed number, and that failed on exactly the records
 * people put on. A modern pop master is compressed: it sits near the top of the scale and hardly
 * moves. Viva la Vida runs at about -9dBFS with a string and synth bed under everything, which on
 * the scale [heardAs] uses is 0.92 - so the edge sat near its widest swing for the length of the
 * song and the little it did move was lost at the top of the range. The trouble was never that the
 * swing was too big. It was that the swing was *always* big, because the level was.
 *
 * So the departure is divided by [spread] before it is used:
 *
 *     out   = (fast - slow) / spread          about 1 for an ordinary departure, 2+ for a hit
 *     level = playing · (MIDDLE + out · reach)
 *
 * A record that barely moves has a small [spread], so the little it does have fills the range; a
 * record that moves a great deal has a large one, so it does not spend the song clipped. The
 * question the edge answers stops being "how loud is this" and becomes **"how unusual is this, for
 * this record"** - which is the question whose answer is visible.
 *
 * What that trades away, so it is not rediscovered as a bug: a compressed record and a dynamic one
 * now look about equally lively. The edge no longer reports how much a piece of music moves, only
 * that it is moving. Nobody watching one phone play one song can see the difference, and being able
 * to see anything at all was worth more.
 *
 * [playing] is what keeps the absolute level in the picture at all, and it is the one thing the
 * amplitude must never stop saying: silence is dark, and a handset playing something soft is
 * visibly smaller than one playing something loud. Above [FULL_AT] it stops mattering.
 *
 * The reach is asymmetric. A rise is the interesting half and gets [REACH_UP]; a fall gets much
 * less, because at the same gain an ordinary dip lands at nearly dark, and dark is the one thing
 * the edge is not allowed to say while this handset is still playing.
 *
 * [punch] is the same `out`, thresholded rather than scaled - see [PUNCH_OVER].
 *
 * All three figures are seeded on the first frame that has any sound in it rather than from zero.
 * [slow] from zero needs several seconds to catch up and every song would open with a flare; and
 * [spread] from zero is worse, because a near-zero divisor makes the first bar arbitrarily large.
 * It is seeded at a fairly lively value instead, so an opening errs towards calm.
 */
private suspend fun followLoudness(
    glow: MutableFloatState,
    punch: MutableFloatState,
    travel: MutableDoubleState,
    speed: Float,
    everyNanos: Long,
    loudness: () -> Float,
) {
    glow.floatValue = 0f
    punch.floatValue = 0f
    var previous: Long? = null
    var fast = 0f
    var slow = 0f
    var spread = 0f
    var seeded = false
    while (true) {
        withInfiniteAnimationFrameNanos { now ->
            val last = previous
            previous = now
            val heard = heardAs(loudness())
            if (last != null) {
                val dt = (now - last) * 1e-9f
                travel.doubleValue += dt.toDouble() * speed
                if (!seeded && heard > 0f) {
                    fast = heard
                    slow = heard
                    spread = SPREAD_SEED
                    seeded = true
                }
                val settle = 1f - exp(-dt / SETTLED_TAU_SECONDS)
                fast += (1f - exp(-dt / LOUDNESS_TAU_SECONDS)) * (heard - fast)
                slow += settle * (heard - slow)
                spread += settle * (abs(fast - slow) - spread)
                // How far out of the ordinary this instant is, in units of this record's own
                // ordinary. About 1 for a typical departure, 2 and up for a hit.
                val out = (fast - slow) / max(spread, SPREAD_FLOOR)
                val playing = (slow / FULL_AT).coerceAtMost(1f)
                val reach = if (out > 0f) REACH_UP else REACH_DOWN
                glow.floatValue = playing * (MIDDLE + out * reach).coerceIn(0f, 1f)
                punch.floatValue = ((out - PUNCH_OVER) / (PUNCH_FULL - PUNCH_OVER)).coerceIn(0f, 1f)
            }
        }
        // The next frame takes the whole gap as its dt, and every step here is in seconds rather
        // than per frame, so a slower pace changes how often the light moves and not how it moves.
        if (everyNanos > 0L) delay(everyNanos / 1_000_000L)
    }
}

/**
 * The renderer's own loudness, 0..1, turned into how loud it *sounds*, 0..1.
 *
 * [com.soundmesh.probe.sync.loudnessOf] hands back a linear RMS of the PCM going to the speaker -
 * the right number for the audio side, and the wrong one to drive a light with. Hearing is
 * logarithmic, and so is the way music is mastered: a record sits somewhere around -14dBFS RMS,
 * which is 0.2 linear, and a quiet passage of the same record around -28dBFS, which is 0.04. Fed
 * straight in, the whole of a song lives in the bottom fifth of the range and every swing that
 * depends on it is a swing of a few per cent. That is why the edge looked like it was ignoring the
 * music: it was following it exactly, inside a band too narrow to see.
 *
 * In decibels the same two numbers are 31/39ths and 17/39ths of this scale, and the difference
 * between them is a third of everything the edge can do.
 *
 * The floor is below any music worth showing and above the noise of a near-silent room; the ceiling
 * is a few dB short of full scale, which nothing mastered ever sustains.
 */
private fun heardAs(rms: Float): Float {
    if (rms <= 0f) return 0f
    val db = 20f * log10(rms)
    return ((db - LOUD_FLOOR_DB) / (LOUD_CEILING_DB - LOUD_FLOOR_DB)).coerceIn(0f, 1f)
}

/**
 * A slow, even breath between [STANDBY_GLOW_MIN] and [STANDBY_GLOW_MAX].
 *
 * Written out rather than left to `rememberInfiniteTransition`, which hands back a State that has
 * to be read somewhere - and the only place to read it was composition, which is the whole of the
 * problem [rememberEdgeGlow] describes. The shape is the same one it had: linear up over
 * [STANDBY_BREATH_MILLIS], linear back down over the same, forever.
 */
private suspend fun breathe(glow: MutableFloatState, travel: MutableDoubleState, speed: Float, everyNanos: Long) {
    val half = STANDBY_BREATH_MILLIS * 1_000_000L
    var start: Long? = null
    var previous: Long? = null
    while (true) {
        withInfiniteAnimationFrameNanos { now ->
            val from = start ?: now.also { start = it }
            val last = previous
            previous = now
            if (last != null) travel.doubleValue += (now - last) * 1e-9 * speed
            // 0..2 through one full there-and-back, so the fold below needs no branch on direction.
            val at = ((now - from) % (2L * half)).toFloat() / half
            val up = if (at <= 1f) at else 2f - at
            glow.floatValue = STANDBY_GLOW_MIN + (STANDBY_GLOW_MAX - STANDBY_GLOW_MIN) * up
        }
        if (everyNanos > 0L) delay(everyNanos / 1_000_000L)
    }
}

/**
 * The time constant the playing edge follows the music with.
 *
 * Not a guess and not a taste: it is the value that reproduces the per-frame 0.25 this replaced,
 * at the 60Hz it was tuned at. `1 - exp(-(1/60) / 0.058) = 0.2504`. Every other refresh rate now
 * gets the same responsiveness in seconds instead of the same fraction per frame.
 */
private const val LOUDNESS_TAU_SECONDS = 0.058f

/**
 * The two characters, in the units [WaveMood] describes.
 *
 * The quiet one is roughly a third of the speed and a quarter of the swing. A handset sitting on a
 * table waiting for the music is meant to read as alive rather than as busy, and at the playing
 * speed and swing a silent room looks like it is already playing something.
 *
 * [PLAYING_MOOD]'s swing never reaches zero at its quiet end: a handset that has a colour is saying
 * so whether or not there is a quiet passage going on, and a ring that flattens into a line during
 * one looks like a handset that has dropped out rather than one playing something soft.
 *
 * The loud end has come down twice, 1.25 then 1.05 and now 0.92, and the quiet end has not moved
 * once. That is not indecision about one number, it is the two ends answering to different things:
 * the quiet end is a floor that has to keep saying "this handset is in the room", and the loud end
 * is a ceiling on how much of the screen a room may take over while somebody is using the handset
 * for something else. Only the ceiling gets renegotiated by looking at it.
 */
private val PLAYING_MOOD = WaveMood(speed = 1.00f, swingQuiet = 0.28f, swingLoud = 0.92f)
private val QUIET_MOOD = WaveMood(speed = 0.35f, swingQuiet = 0.22f, swingLoud = 0.42f)

/**
 * How long "where the music has been sitting, and how much it moves" looks back.
 *
 * A few seconds because that is about the length of a phrase: long enough that a verse and a chorus
 * are two different levels rather than one, short enough that the edge re-centres within a bar or
 * two of a song changing character.
 */
private const val SETTLED_TAU_SECONDS = 2.5f

/**
 * Where the edge sits when the music is doing exactly what it has been doing, and how far one
 * ordinary departure from that moves it up and down.
 *
 * The middle, not the top: everything above it is the room the record needs to be louder than
 * itself, and a first cut that had no such room is the whole reason this file works the way it
 * does. [REACH_UP] is as large as it can be before ordinary passages spend their time pinned at
 * full, which would throw away the difference between a big hit and a small one; measured against
 * the traces in [PUNCH_OVER], a dense compressed record is at full about a twentieth of the time.
 */
private const val MIDDLE = 0.5f
private const val REACH_UP = 0.30f
private const val REACH_DOWN = 0.18f

/**
 * What `spread` starts at, and how small it is allowed to get.
 *
 * The seed is on the lively side so that the first bar of a song is calm rather than wild - a
 * divisor that starts too small makes an opening arbitrarily large, which is the one failure this
 * arrangement can produce that the fixed-gain one could not.
 *
 * The floor is about 0.4dB. Below it the material is not quiet, it is *flat*, and there is nothing
 * there to show: dividing by a smaller number than this only amplifies whatever numerical dither
 * is left, and an edge dancing to dither is worse than an edge holding still.
 */
private const val SPREAD_SEED = 0.06f
private const val SPREAD_FLOOR = 0.010f

/**
 * The level at and above which this handset counts as fully playing, on [heardAs]'s scale.
 *
 * About -24dBFS. This is the only place absolute loudness still enters, and its whole job is the
 * bottom of the range: silence dark, something soft visibly smaller than something loud. Above it,
 * how loud the record is stops changing the picture, which is the point.
 */
private const val FULL_AT = 0.55f

/**
 * How far out of the ordinary an instant has to be before the lead wave flashes, and where the
 * flash reaches full.
 *
 * A threshold rather than a gain, and that distinction is what makes this worth having. Dividing by
 * `spread` already means an ordinary departure scores about 1 whatever record is playing - so a
 * gain would hand every record a permanent half-flash, which is not a flash, it is brightness. Only
 * what stands out *against this record's own ordinary* gets through. That is also how real onset
 * detectors work: a threshold over a moving average, not a fixed level.
 *
 * **This is not beat detection and does not know what a beat is.** It has no tempo, no grid, no
 * idea which hit is the downbeat, and it cannot tell a kick from a consonant or a bowed attack -
 * it only knows that this instant is louder than the last few seconds were. Beat tracking proper
 * means a spectral flux and a comb filter or autocorrelation over several seconds, which means an
 * FFT on the audio thread, which is a great deal of machinery and risk on the one path in this app
 * that must not be disturbed. The thing actually being asked for is that somebody watching should
 * be in no doubt the edge is listening, and for that a rise is enough.
 *
 * What it will get wrong, so that the next person does not rediscover it: a kick drum is mostly
 * low frequency, and this reads the whole band, so on a track with a loud vocal or a wall of guitar
 * the kick can pass without moving the total much. The fix, if it ever proves worth it, is a
 * one-pole low pass before the RMS - cheap in cycles, but it lives in the renderer, on the audio
 * thread, and that is a different order of risk from anything in this file.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * Where all of these numbers come from, since none of them could be measured here
 * ────────────────────────────────────────────────────────────────────────────
 * Not tuned by eye and not guessed: the loop above was reimplemented as a few lines of script and
 * run against loudness traces the bench cannot produce, because getting real music into this app
 * takes a consent dialog nobody can automate. Each row is the swing the waves end up with, the lead
 * wave's brightness, and how much of the time that wave is flashing, after the first six seconds:
 *
 *     -9dB, dense bed, a hit every 480ms    swing 0.37..0.92 mean 0.63  lead 0.24..0.86  punch 5%
 *     the same bed with the hits taken out  swing 0.42..0.90 mean 0.64  lead 0.27..0.73  punch 0%
 *     -22dB swinging +-6dB, slowly          swing 0.41..0.91 mean 0.64  lead 0.27..0.74  punch 0%
 *     held -22dB note, 1Hz vibrato +-2dB    swing 0.42..0.90 mean 0.64  lead 0.27..0.74  punch 0%
 *     held -22dB note, dead flat            swing 0.60 flat             lead 0.41 flat   punch 0%
 *     -12dB for six seconds, then -26dB     swing 0.53..0.55, never near dark
 *     a quiet room at -40dB                 swing 0.35 flat - present, and plainly not music
 *     silence, then the dense bed           opens at 0.78 of a possible 0.92: no startup flare
 *
 * Rows one and two are the whole case for the threshold: the same bed scores the same *swing*
 * either way, and the flash is what separates the one with beats in it from the one without. Row
 * five is the control - material that genuinely does not move reads as still, so the edge is
 * reading the music rather than inventing motion. Row one is at full a twentieth of the time, which
 * is what [REACH_UP] is set against.
 *
 * The table is re-run rather than rescaled whenever the ends move, because the swing column is not
 * proportional to anything: it is `swingQuiet + (swingLoud - swingQuiet) * level`, so lowering the
 * ceiling alone moves every row by a different amount and leaves the floor alone. The punch column
 * is the one thing a change of ends cannot touch.
 */
private const val PUNCH_OVER = 1.2f
private const val PUNCH_FULL = 2.8f

/**
 * The two ends of the range [heardAs] stretches music across, in dBFS.
 *
 * The floor is a room with something quiet going on in it rather than a room with nothing in it;
 * the ceiling is where a master's loudest passages sit. Between them is everything the edge has to
 * say, so widening this band flattens the effect and narrowing it makes the edge clip on every hit.
 */
private const val LOUD_FLOOR_DB = -45f
private const val LOUD_CEILING_DB = -6f

/** The disconnected sink's fixed, unbreathing amplitude. */
private const val DISCONNECTED_GLOW = 0.15f

private const val STANDBY_GLOW_MIN = 0.35f
private const val STANDBY_GLOW_MAX = 0.75f
private const val STANDBY_BREATH_MILLIS = 2000L
