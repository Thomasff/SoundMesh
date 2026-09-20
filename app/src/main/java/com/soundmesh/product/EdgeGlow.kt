package com.soundmesh.product

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableDoubleState
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.soundmesh.session.SessionService
import kotlin.math.exp
import kotlin.math.log10

/**
 * Whether this handset is a sink that has dropped off its standing line while nothing is playing.
 *
 * Shared between [rememberEdgeGlow] (which picks the flat, unbreathing amplitude for this state)
 * and [HomeScreen] (which dims the edge colour itself for it) so the two cannot disagree about
 * which state they are drawing.
 */
internal fun disconnectedSink(state: HomeState): Boolean = !state.onStandby && state.role == Role.SINK

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
 * - **playing** - read off [SessionService.ACTIVE]'s own loudness every frame, smoothed so the
 *   edge follows the music rather than flickering with every 20ms chunk.
 * - **standing by, not playing** - a slow, even breath, so a phone left in a corner still reads
 *   as alive from across the room.
 *
 * Deliberately not read off [HomeState] itself: the screen's own state is polled five times a
 * second, which is far too slow for something meant to move with the music, and lifting that
 * whole poll to a frame rate to carry one float would put the cost of this decoration on a path
 * that has to stay cheap for reasons that have nothing to do with it. This reads the renderer's
 * volatile directly instead - see [com.soundmesh.probe.sync.SyncRenderer.loudness].
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
internal fun rememberEdgeGlow(state: HomeState, lit: Boolean): EdgeGlow {
    val glow = remember { mutableFloatStateOf(STANDBY_GLOW_MIN) }
    val travel = remember { mutableDoubleStateOf(0.0) }
    val mood: MutableState<WaveMood> = remember { mutableStateOf(QUIET_MOOD) }
    val regime = when {
        !lit -> Glow.DARK
        disconnectedSink(state) -> Glow.DISCONNECTED
        state.running -> Glow.PLAYING
        else -> Glow.STANDBY
    }
    LaunchedEffect(regime) {
        mood.value = if (regime == Glow.PLAYING) PLAYING_MOOD else QUIET_MOOD
        // The travel accumulator is deliberately not reset here. See [EdgeGlow.travelSeconds].
        when (regime) {
            // No loop: nothing on screen is reading this, and the frame the loop would ask for is
            // a frame the whole app would otherwise not have drawn.
            Glow.DARK -> Unit
            // No loop here either, so the waves hold still where they were - which is the same
            // statement the flat amplitude is making.
            Glow.DISCONNECTED -> glow.floatValue = DISCONNECTED_GLOW
            Glow.PLAYING -> followLoudness(glow, travel, PLAYING_MOOD.speed)
            Glow.STANDBY -> breathe(glow, travel, QUIET_MOOD.speed)
        }
    }
    return remember(glow, travel, mood) {
        object : EdgeGlow {
            override fun amplitude(): Float = glow.floatValue
            override fun travelSeconds(): Double = travel.doubleValue
            override fun mood(): WaveMood = mood.value
        }
    }
}

/**
 * Follows [SessionService.ACTIVE]'s own loudness, one exponential step per frame.
 *
 * ★ The step is a time constant, not a per-frame fraction. It used to be `smoothed += 0.25f *
 * (target - smoothed)`, which is a different amount of smoothing on every refresh rate the app
 * runs at - the edge was twice as sluggish on the 120Hz handset as on the 60Hz one, for no reason
 * anybody chose. [LOUDNESS_TAU_SECONDS] is picked so that at 60Hz this is the old 0.25 to three
 * decimal places, which is what makes the change a no-op on the handset it was tuned on.
 *
 * Starts from zero rather than from whatever the breath left behind, which is what it did before:
 * the edge easing up from dark as the first bar plays is the behaviour, not an artefact of how it
 * used to be written.
 */
private suspend fun followLoudness(glow: MutableFloatState, travel: MutableDoubleState, speed: Float) {
    glow.floatValue = 0f
    var previous: Long? = null
    while (true) {
        withInfiniteAnimationFrameNanos { now ->
            val last = previous
            previous = now
            val target = heardAs(SessionService.ACTIVE?.loudness() ?: 0f)
            if (last != null) {
                val dt = (now - last) * 1e-9f
                travel.doubleValue += dt.toDouble() * speed
                val k = 1f - exp(-dt / LOUDNESS_TAU_SECONDS)
                glow.floatValue += k * (target - glow.floatValue)
            }
        }
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
private suspend fun breathe(glow: MutableFloatState, travel: MutableDoubleState, speed: Float) {
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
 */
private val PLAYING_MOOD = WaveMood(speed = 1.00f, swingQuiet = 0.28f, swingLoud = 1.25f)
private val QUIET_MOOD = WaveMood(speed = 0.35f, swingQuiet = 0.22f, swingLoud = 0.42f)

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
