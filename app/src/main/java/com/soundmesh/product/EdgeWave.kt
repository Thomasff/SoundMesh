package com.soundmesh.product

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Which edge of the screen a band is on. Geometry is the whole of what differs between them. */
private enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

/**
 * The four edges of the screen, lit in this handset's own colour, with sine waves travelling
 * along them.
 *
 * The colour is the handset's name, and a name is only useful where the thing it names is. A chip
 * at the top of a screen names the phone to whoever is holding it; a room of phones lying face up
 * on tables and shelves is looked at from a chair several metres away, and from there a 28dp
 * circle is nothing. The whole edge of a screen is the largest thing a phone can say from that
 * distance without covering up what it is saying it about.
 *
 * Called only where there is a colour to draw. A handset before its host has a room to hand
 * colours out in draws no edge at all, and a default colour would be worse than none: two handsets
 * sharing one is exactly the confusion the colours exist to end.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * The waves are not synchronised between handsets, on purpose
 * ────────────────────────────────────────────────────────────────────────────
 * Phase carries no information: the seventh crest and the ninth crest are the same thing to
 * anybody looking. What carries information is the amplitude - whether this handset is sounding -
 * and that is each handset's own loudness, which is already in step with every other handset's
 * because they are playing the same music at the same instant. So the phase runs off a local
 * clock that restarts when playback does, and nothing about it crosses the network.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * What this costs, and the one sentence that predicts it
 * ────────────────────────────────────────────────────────────────────────────
 * Before the waves, four gradient bands cost 1.6% of a core over a screen that was already being
 * redrawn sixty times a second, and five different ways of making that drawing cheaper each made
 * no measurable difference. It was reasonable to read that as "drawing is free here". It is not:
 * it was free at four filled rectangles a frame, and strokes are a different animal. Measured on
 * the X10 (Android 10, 60Hz) on the host's status screen, all at 60Hz, one variable at a time:
 *
 *     nothing lit, nothing animating                         p50  --    no frames at all   4.2%
 *     four gradient bands, no waves                          p50 10ms   janky   1%        24.3%
 *     3 waves, skirt shared, 5 strokes a band                p50 15ms   janky  34%        26.1%
 *     6 waves, 1 stroke each, 6 a band                       p50 15ms   janky  38%        27.4%
 *     6 waves, skirt shared, 6 strokes a band                p50 36ms   janky 100%        20.5%
 *     6 waves, 3 nested strokes each, 12 a band              p50 19ms   janky  79%        23.3%
 *     6 waves, 4 nested strokes each, 24 a band              p50 34ms   janky 100%        16.0%
 *
 * What does *not* predict the cost: the blend mode (adding and laying over measured identically),
 * the corner clip (removing it changed nothing), and, at a given stroke count, three times the
 * sample points (15%). What does predict it is **how much stroked outline there is in the frame** -
 * contours times their length times their width, summed over every stroke. Six waves at three
 * nested strokes is twice three waves at the same, and it measures twice.
 *
 * Note the last column: the main thread gets *cheaper* as the picture gets more expensive, because
 * a screen that cannot finish a frame in 16.7ms simply asks for fewer of them. Main-thread CPU is
 * not a measure of how heavy a frame is, and reading it as one would have pointed the opposite way
 * every time.
 *
 * At 15ms this fits a 60Hz frame with almost nothing to spare, which is why there are three waves
 * and not six. [WAVE_SHOWN] is the knob, and a faster handset can afford more of them.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * What this ruler can and cannot resolve
 * ────────────────────────────────────────────────────────────────────────────
 * The table above is safe because every row differs from its neighbours by a factor, not a few per
 * cent. Below that, a thirty-second `gfxinfo` run on this handset resolves nothing. The same
 * installed package, three runs back to back, nothing touched in between:
 *
 *     p50 14ms janky 13%     p50 17ms janky 56%     p50 15ms janky 33%
 *
 * That spread is wider than every fine-grained comparison made while the rounded corners and the
 * edge-to-edge window were being added - each of which was one run against one run, and each of
 * which was written up here as a finding before these three were run. They are withdrawn: whether
 * a quarter-circle corner costs a millisecond, whether 4dp off each band's depth is worth anything,
 * and whether sampling the arcs finely is free are all **unmeasured**, not measured-and-small. To
 * settle any of them takes repeated runs and a comparison of medians, and none of them changed a
 * decision here - the corners and the full-screen ring were wanted for how they look on a phone
 * with round glass and a clock at the top.
 *
 * What survives is structural rather than numeric, and it is enough to design against: the cost is
 * in the outline, a quarter circle is about half again as long as the corner it replaces, and the
 * whole screen is about a tenth taller than the part below the status bar. Both make the ring
 * longer, so both cost something; how much is not known to better than the noise.
 */
@Composable
internal fun BoxScope.BadgeEdges(colour: Color, glow: EdgeGlow) {
    // Read fresh rather than remembered: on the first composition the view may not be attached to a
    // window yet, and a remembered null would keep the fallback radius for the life of the screen.
    val round = screenCornerPx(LocalView.current, LocalDensity.current)
    BoxWithConstraints(modifier = Modifier.matchParentSize()) {
        val wide = maxWidth
        val tall = maxHeight
        for (edge in Edge.values()) {
            val place = when (edge) {
                Edge.TOP -> Alignment.TopCenter
                Edge.BOTTOM -> Alignment.BottomCenter
                Edge.LEFT -> Alignment.CenterStart
                Edge.RIGHT -> Alignment.CenterEnd
            }
            val extent = when (edge) {
                Edge.TOP, Edge.BOTTOM -> Modifier.fillMaxWidth().height(EDGE_NODE)
                Edge.LEFT, Edge.RIGHT -> Modifier.fillMaxHeight().width(EDGE_NODE)
            }
            Spacer(
                modifier = Modifier
                    .align(place)
                    .then(extent)
                    .drawWithCache {
                        // Everything here depends on the size and on nothing that moves, so it is
                        // built once per layout rather than sixty times a second. Only the paths
                        // are rebuilt per frame, and even those reuse their storage.
                        val shape = bandShape(edge, size, wide.toPx(), tall.toPx(), density, round)
                        onDrawBehind { edgeWaves(shape, colour, glow) }
                    }
            )
        }
    }
}

/**
 * Everything about one band that the screen's size decides and no frame changes.
 *
 * [mitre] clips the band to the part of the perimeter nearer to its own edge than to either
 * neighbour - a 45° cut from each screen corner. Without it every corner is a square of two bands
 * laid over each other, so the four corners read as brighter than the four sides and the ring does
 * not look like one ring.
 *
 * [uBase] and [uSign] turn a distance along this band into a position around the whole perimeter,
 * 0..1. That is the coordinate the wave table is tuned in: `m` is a number of crests per lap, so a
 * wave keeps its density on any screen, and the same table works on a tall phone and a wide one.
 */
private class BandShape(
    val edge: Edge,
    val mitre: Path,
    /** Across the band: 0 at the screen edge, growing inward. */
    val node: Float,
    /** Along the band, in pixels. */
    val along: Float,
    val uBase: Float,
    val uSign: Float,
    val perimeter: Float,
    val bandPx: Float,
    val sigmaPx: Float,
    /** Radius of the screen's own corner arc, in pixels. See [screenCornerPx]. */
    val round: Float,
    /** Design-sheet pixels to this screen's pixels, for the two tables quoted in design units. */
    val geoK: Float,
    /** Where the swing starts from nothing, and how far past that it takes to reach full. */
    val taperFrom: Float,
    val taperOver: Float,
    val samples: Int,
    val points: FloatArray,
    /** One per wave, plus one holding them all for the shared skirt. */
    val paths: List<Path>,
)

private fun bandShape(
    edge: Edge,
    size: Size,
    wide: Float,
    tall: Float,
    density: Float,
    round: Float,
): BandShape {
    val flat = edge == Edge.TOP || edge == Edge.BOTTOM
    val node = if (flat) size.height else size.width
    val along = if (flat) size.width else size.height
    val far = along - node
    val mitre = Path().apply {
        when (edge) {
            Edge.TOP -> { moveTo(0f, 0f); lineTo(along, 0f); lineTo(far, node); lineTo(node, node) }
            Edge.BOTTOM -> { moveTo(0f, node); lineTo(along, node); lineTo(far, 0f); lineTo(node, 0f) }
            // Both mitres half a pixel short, which is [SEAM]'s whole job.
            Edge.LEFT -> {
                moveTo(0f, SEAM); lineTo(node, node + SEAM)
                lineTo(node, far - SEAM); lineTo(0f, along - SEAM)
            }
            Edge.RIGHT -> {
                moveTo(node + SEAM, 0f); lineTo(SEAM, node)
                lineTo(SEAM, far); lineTo(node + SEAM, along)
            }
        }
        close()
    }
    // Clockwise from the top-left corner. The two far edges run backwards along their own node,
    // which is the whole of why [uSign] exists.
    val uBase = when (edge) {
        Edge.TOP -> 0f
        Edge.RIGHT -> wide
        Edge.BOTTOM -> 2f * wide + tall
        Edge.LEFT -> 2f * wide + 2f * tall
    }
    val uSign = if (edge == Edge.TOP || edge == Edge.RIGHT) 1f else -1f
    val points = samplePoints(along, round, density)
    return BandShape(
        edge = edge,
        mitre = mitre,
        node = node,
        along = along,
        uBase = uBase,
        uSign = uSign,
        perimeter = 2f * (wide + tall),
        bandPx = WAVE_BAND_DP * density,
        sigmaPx = WAVE_SIGMA_DP * density,
        round = round,
        geoK = WAVE_PEAK_AMP_DP * density / 7.8f,
        // Nothing swings inside the mitre, which is exactly the part two bands share - nor anywhere
        // in the corner arc, which is what keeps a wave from swinging across a bend and leaves the
        // three lines running round it as three plain concentric arcs.
        taperFrom = max(node, round),
        taperOver = node * 3f,
        samples = points.size - 1,
        points = points,
        paths = List(WAVE_SHOWN.size + 1) { Path() },
    )
}

/**
 * Where along the band to put the polylines' vertices.
 *
 * One point every 12dp along the straight run: the shortest wave still shown, m=20, is about 340px
 * a crest on a handset, so that is nine points a crest - and finer sampling measured almost free
 * but also looked no different.
 *
 * The corners are sampled about twice as finely, because there the points are not evenly spaced
 * along what is drawn. A corner squeezes `round` pixels of this coordinate onto an arc half again
 * as long, so the same step would put a visible flat on every bend. Twice as fine over two stretches
 * of 36dp is about forty per cent more points on a phone-width band, which by the measurements in
 * [BadgeEdges] is worth a couple of per cent of a frame - and it is paid once per layout, not per
 * frame, because the array is built here and reused.
 */
private fun samplePoints(along: Float, round: Float, density: Float): FloatArray {
    val bend = round.coerceIn(0f, along / 3f)
    val straight = along - 2f * bend
    val corner = ceil(bend * QUARTER_TURN / (6f * density)).toInt().coerceIn(1, 24)
    val middle = (straight / (12f * density)).toInt().coerceIn(2, 120)
    val marks = FloatArray(2 * corner + middle + 1)
    for (k in 0 until corner) marks[k] = bend * k / corner
    for (k in 0..middle) marks[corner + k] = bend + straight * k / middle
    for (k in 1..corner) marks[corner + middle + k] = along - bend + bend * k / corner
    return marks
}

/**
 * The radius of this screen's own rounded corner, in pixels.
 *
 * Android has only known its own corner radius since API 31, and it is not a number that can be
 * derived from anything else - it is a property of the glass. So: ask when there is something to
 * ask, and otherwise use a radius typical of the handsets this runs on. Getting it wrong by a few
 * dp costs nothing visible; the light is 10dp in from the edge and its own bend is that much
 * gentler than the glass's.
 *
 * Clamped at the top because [EDGE_NODE] has to contain the deepest point of the arc, which sits
 * where the two bands meet, at `round - (round - restingDepth)/√2` in from the edge.
 */
private fun screenCornerPx(view: View, density: Density): Float {
    val asked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
    } else {
        null
    }
    return with(density) {
        (asked ?: CORNER_FALLBACK_DP.toPx()).coerceIn(0f, CORNER_MOST_DP.toPx())
    }
}

/**
 * One frame of one band.
 *
 * The depth of wave `i` at a point `u` around the perimeter is
 *
 *     c = band + OFF[i]·geoK + AMP[i]·geoK·uAmp·sin(2π·M[i]·(u − DIR[i]·CYC[i]·t) + PHASE[i])·taper
 *
 * The band's own depth is fixed and only the swing follows the music. Letting the depth breathe as
 * well - which is what this did before there were waves - stirs up the one thing the waves are for,
 * which is a sense of something travelling along a band that is holding still.
 *
 * The swing tapers to nothing at both ends of the band, which is this drawing's substitute for the
 * design's corner-convergence term. The design keeps one continuous ribbon around a rounded
 * rectangle and has to damp the swing near each corner arc, or the phone reads as if it were
 * twisting. Four independent bands cannot be continuous across a corner in the first place, so
 * instead the swing is simply zero where two bands meet - and a corner where nothing swings is a
 * corner where there is nothing to be discontinuous.
 */
private fun DrawScope.edgeWaves(band: BandShape, colour: Color, glow: EdgeGlow) {
    val lit = glow.amplitude()
    val hit = glow.punch()
    val mood = glow.mood()
    val seconds = glow.travelSeconds()
    val swing = mood.swingQuiet + (mood.swingLoud - mood.swingQuiet) * lit
    val fade = WAVE_FADE_DARK + (1f - WAVE_FADE_DARK) * lit
    val every = band.paths[WAVE_SHOWN.size].also { it.reset() }
    for (shown in WAVE_SHOWN.indices) {
        val i = WAVE_SHOWN[shown]
        // Reduced into one period of this wave before it ever reaches a Float. A whole period is
        // an exact multiple of 2π·M[i] of phase because M is a whole number of crests per lap, so
        // this is not an approximation - it is the same angle, computed small.
        val period = 1.0 / WAVE_CYC[i]
        val travel = WAVE_DIR[i] * WAVE_CYC[i] * (seconds % period)
        val base = band.bandPx + WAVE_OFF[i] * band.geoK
        val reach = WAVE_AMP[i] * band.geoK * swing
        val path = band.paths[shown].also { it.reset() }
        for (s in 0..band.samples) {
            val at = band.points[s]
            val u = (band.uBase + band.uSign * at) / band.perimeter
            val angle = 2.0 * PI * WAVE_M[i] * (u - travel) + WAVE_PHASE[i]
            val deep = base + reach * sin(angle).toFloat() * taper(at, band)
            val point = bend(band, at, deep)
            if (s == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        every.addPath(path)
    }
    clipPath(band.mitre) {
        // The skirt first, once for every wave at an opacity between theirs. Widest step first, so
        // each narrower one adds onto the ones under it.
        val skirt = lerp(colour, Color.White, (SKIRT_OP * 1.15f - 0.35f).coerceIn(0f, WAVE_WHITEST))
        for (step in SKIRT_AT.indices) {
            drawPath(
                every, skirt,
                alpha = (SKIRT_OP * WAVE_GAIN * SKIRT_ALPHA[step]).coerceAtMost(1f) * fade,
                style = Stroke(band.sigmaPx * SKIRT_WID * 2f * SKIRT_AT[step]),
                blendMode = WAVE_BLEND,
            )
        }
        // Then each wave's own core on top, which is where the brightness that tells the waves
        // apart actually lives.
        for (shown in WAVE_SHOWN.indices) {
            val i = WAVE_SHOWN[shown]
            val core = lerp(colour, Color.White, (WAVE_OP[i] * 1.15f - 0.35f).coerceIn(0f, WAVE_WHITEST))
            // The lead wave brightens with the music on top of what the whole ring does, and flashes
            // on a hit; the rest keep the ring's own fade. See [LEAD_DIM].
            val beat =
                if (shown == WAVE_SHOWN.lastIndex) LEAD_DIM + LEAD_LEVEL * lit + LEAD_PUNCH * hit
                else 1f
            drawPath(
                band.paths[shown], core,
                alpha = (WAVE_OP[i] * WAVE_GAIN * CORE_ALPHA).coerceAtMost(1f) * fade * beat,
                style = Stroke(band.sigmaPx * WAVE_WID[i] * 2f * CORE_AT),
                blendMode = WAVE_BLEND,
            )
        }
    }
}

/** Full swing in the middle of a band, none at either end, with no corner in the slope. */
private fun taper(at: Float, band: BandShape): Float {
    val past = min(at, band.along - at) - band.taperFrom
    val k = (past / band.taperOver).coerceIn(0f, 1f)
    return k * k * (3f - 2f * k)
}

/**
 * A distance along the band and a depth in from the screen edge, as a point in the node - with the
 * band's two ends bent round the screen's own corner arc.
 *
 * Handsets have rounded glass, and three straight lines meeting three straight lines at a right
 * angle in the corner of one is the one place the whole effect stops looking like part of the
 * phone. A curve inset by `deep` from a rounded rectangle of radius `round` is a rounded rectangle
 * of radius `round - deep`, so each of the three lines gets its own concentric arc for free - one
 * subtraction and a sine and cosine per sample, which by the measurements in [BadgeEdges] is not
 * what a frame's cost is made of.
 *
 * The half-arc each band draws is exactly the half its mitre already kept: the mitre's diagonal is
 * the line y == x out of the screen corner, and that line passes through the 45° point of every
 * concentric arc whatever its radius. So the two bands still meet on a curve, and meet without a
 * kink, with the clip unchanged.
 *
 * Nothing swings here - `taperFrom` is at least `round` - which is what keeps the arcs concentric
 * and keeps the deepest of them inside the node.
 */
private fun bend(band: BandShape, at: Float, deep: Float): Offset {
    val r = band.round
    val reach = (r - deep).coerceAtLeast(0f)
    return when {
        at < r -> {
            val turn = QUARTER_TURN * (at / r)
            place(band, r - reach * cos(turn), r - reach * sin(turn))
        }
        at > band.along - r -> {
            val turn = QUARTER_TURN * ((band.along - at) / r)
            place(band, band.along - r + reach * cos(turn), r - reach * sin(turn))
        }
        else -> place(band, at, deep)
    }
}

/** A distance along the band and a depth in from the screen edge, as a point in the node. */
private fun place(band: BandShape, at: Float, deep: Float): Offset = when (band.edge) {
    Edge.TOP -> Offset(at, deep)
    Edge.BOTTOM -> Offset(at, band.node - deep)
    Edge.LEFT -> Offset(deep, at)
    Edge.RIGHT -> Offset(band.node - deep, at)
}

/**
 * The wave table, tuned by eye against a live preview and copied here unchanged.
 *
 * Read across a row for one wave. They are ordered by [WAVE_OP]: the faintest wave is also the
 * shortest and the fastest, and the brightest is the longest and the slowest. Drawing a high
 * wavenumber brightly turns the whole ring into fine bristles, so this order is load-bearing.
 *
 * - [WAVE_M] crests per lap of the perimeter. This is the invariant: the wavelength in pixels
 *   follows the screen, the crest count does not, which is what lets one table fit every handset.
 * - [WAVE_CYC] laps per second, signed by [WAVE_DIR]. Speed has to be laps rather than pixels for
 *   the same reason.
 * - [WAVE_AMP] and [WAVE_OFF] are design-sheet pixels, scaled by `geoK`. [WAVE_OFF] spaces the
 *   resting depths out; [WAVE_AMP] is how far each one swings.
 */
private val WAVE_M = floatArrayOf(27f, 20f, 15f, 11f, 8f, 6f)
private val WAVE_AMP = floatArrayOf(5.1f, 5.4f, 6.0f, 6.6f, 7.1f, 7.8f)
private val WAVE_CYC = doubleArrayOf(0.1005, 0.0804, 0.0643, 0.0510, 0.0416, 0.0308)
private val WAVE_DIR = doubleArrayOf(-1.0, 1.0, -1.0, 1.0, -1.0, 1.0)
private val WAVE_OP = floatArrayOf(0.15f, 0.22f, 0.32f, 0.46f, 0.66f, 0.94f)
private val WAVE_PHASE = doubleArrayOf(6.0657, 2.4853, 1.2267, 2.3500, 5.7262, 2.5407)
private val WAVE_OFF = floatArrayOf(-0.228f, -2.157f, 0.474f, -1.200f, -1.304f, -0.101f)
private val WAVE_WID = floatArrayOf(1.00f, 1.00f, 1.08f, 1.16f, 1.28f, 1.40f)

/**
 * Which of the six the handset actually draws, and the only performance knob here worth having.
 *
 * All six cost twice what three do and measure twice - see [BadgeEdges] - and on the X10 six do
 * not fit in a 60Hz frame at any arrangement of strokes that still looks like light rather than
 * like wire. Three do, at 15ms of a 16.7ms frame.
 *
 * Every other wave, counting from the slowest: the brightest and longest, then alternating
 * directions and roughly halving the wavelength each time. That keeps the design's spread of
 * speeds and its alternating [WAVE_DIR], which is what stops the ring reading as one sliding
 * shape, and it drops the two waves nobody was going to pick out anyway.
 */
private val WAVE_SHOWN = intArrayOf(1, 3, 5)

/** Where the waves rest, and how far the widest of them can reach past that. */
private const val WAVE_BAND_DP = 10.0f
private const val WAVE_PEAK_AMP_DP = 7.6f

/** Half-width of a wave's core stroke, before [WAVE_WID] widens it per wave. */
private const val WAVE_SIGMA_DP = 0.5f

private const val WAVE_GAIN = 1.70f

/**
 * How a wave gets a soft edge on a canvas that has no per-pixel maths in it, in five strokes a
 * band rather than twelve.
 *
 * The effect was designed against a shader where a wave's brightness at distance t from its centre
 * is exp(-t²) + 0.30·exp(-0.09t²) - a bright core inside a glow three times as wide. A stroke is a
 * ribbon of one flat alpha with a hard edge, so one stroke cannot be that curve, and the two the
 * design's canvas fallback called for read as wire rather than as light: at the width a wave
 * needs, two hard edges are two hard edges. Nested strokes under an adding blend sum to a
 * staircase, and a staircase with enough steps is the curve.
 *
 * Enough steps is four a wave, which does not fit the frame. So the steps are split by what they
 * carry. The skirt - the two outer steps - is where the softness is and carries a fifth of the
 * brightness, so every wave shares it at one opacity between theirs, in one stroke per step. The
 * core carries the other four fifths and is where the difference between a faint wave and a bright
 * one is read, so each wave keeps its own.
 *
 * The numbers are the curve's drop across each step: the skirt totals its value at 2σ, the core
 * the rest.
 */
private val SKIRT_AT = floatArrayOf(3.5f, 2.0f)
private val SKIRT_ALPHA = floatArrayOf(0.0995f, 0.1275f)
private const val SKIRT_OP = 0.45f
private const val SKIRT_WID = 1.20f
private const val CORE_AT = 0.5f
private const val CORE_ALPHA = 0.845f

/**
 * How far towards white the brightest wave is allowed to go.
 *
 * The design takes it to 0.85, which is right where the effect came from: an icon, where the ring
 * is decoration and nobody has to name its colour. Here the colour *is* the name - it is the whole
 * reason the edge is lit at all - and at 0.85 a purple handset and a green one both read as white
 * from across a room. Enough white to say "this is the bright one", not enough to lose the hue.
 */
private const val WAVE_WHITEST = 0.25f

/**
 * How the lead wave's brightness is split three ways: what it keeps whatever the music does, what
 * follows the level, and what the hits get.
 *
 * The swing alone says "this handset is sounding" but says it in a way that takes a second or two
 * of watching to read, because the eye is being asked to compare a shape against the same shape a
 * moment ago. Brightness is read instantly and without a reference. So the one wave already carrying
 * the ring's identity - the brightest, widest, slowest, the last one drawn - brightens with the
 * music on top of what the whole ring does, and flashes on [EdgeGlow.punch].
 *
 * Only that one. All three doing it is the whole ring pulsing, which is a different effect and a
 * more tiring one; the two behind it holding steady is what makes the lead read as moving against
 * something.
 *
 * **The three add to less than one and are never clamped**, which is deliberate on both counts. No
 * clamp, because a clamp would mean that during a loud passage - where the level term is already
 * near full - a hit changes nothing, and the beats would disappear from precisely the part of a
 * song that has the most of them. Keeping a share of the range reserved for the punch is what keeps
 * it visible at every level. And less than one, because the ceiling came down by an eighth after a
 * listen on two handsets while [LEAD_DIM] stayed where it was: the floor is what the ring says
 * about itself with no music, and only the top of the range was ever too much.
 *
 * See [EdgeGlow.punch] for what the punch is and, more importantly, what it is not.
 */
private const val LEAD_DIM = 0.45f
private const val LEAD_LEVEL = 0.27f
private const val LEAD_PUNCH = 0.16f

/** What is left of the brightness with nothing sounding. */
private const val WAVE_FADE_DARK = 0.40f

/**
 * Added rather than laid over, because the waves are one glow rather than several ribbons.
 *
 * The brightest wave is also the last one drawn and the widest; over the top it would hide the
 * others instead of summing with them. Measured, this costs exactly what laying over costs.
 */
private val WAVE_BLEND = BlendMode.Plus

/**
 * The depth of each band's node: the deepest any wave reaches, plus its skirt.
 *
 * Fixed rather than following the music, so that the swing moves pixels and never the layout.
 *
 * Also the damage rectangle each frame repaints, and that is worth about four points of jank per
 * 4dp - see [BadgeEdges]. So it is as shallow as the corners allow rather than a round number:
 * where two bands meet, the arcs reach `round - (round - restingDepth)/√2` in from the edge, the
 * waves rest about 12dp in with their skirt, and at [CORNER_MOST_DP] that is 22.5dp.
 */
private val EDGE_NODE = 24.dp

/**
 * What to assume the glass is rounded by when the platform will not say, and how round it is
 * allowed to claim to be.
 *
 * The fallback is the middle of the range handsets of this generation actually use. The ceiling is
 * what [EDGE_NODE] can hold; a phone rounder than this gets a slightly tighter bend than its glass,
 * which is invisible next to the sharp corner it replaces.
 */
private val CORNER_FALLBACK_DP = 32.dp
private val CORNER_MOST_DP = 48.dp

private const val QUARTER_TURN = (PI / 2.0).toFloat()

/**
 * Half a pixel, by which the side bands' mitres fall short of the diagonal they share with the
 * flat ones.
 *
 * The clip is a hard test against the pixel's centre, and the corner diagonal runs x == y - which
 * is exactly where those centres sit. Both bands then claim that one row of pixels and draw it
 * twice: measured on the X10 at 24dp deep, the diagonal came back at luminance 62 against 50 on
 * either side of it - a bright hairline out of each corner. Half a pixel is enough to put the
 * boundary somewhere no pixel centre is, and a whole one would leave a dark hairline instead.
 */
private const val SEAM = 0.5f
