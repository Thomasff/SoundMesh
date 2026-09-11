package com.soundmesh.product

import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

/**
 * Where [RoomFit.DEFAULT_PRIOR_WEIGHT] comes from, and whether correcting is worth doing at all.
 *
 * Synthetic rooms rather than argument, because the two errors being traded off are not the same
 * shape: a sketch is wrong by a fraction - somebody who thinks a handset is half again as far out
 * as it is - and a measurement is wrong by a length, about the same length whether the room is
 * small or large.
 *
 * What is scored is what playback actually reads. Nothing downstream sees a position: every gain
 * in the system comes out of SpatialLayout.distanceGainOf, which is one handset's radius from the
 * listener over the furthest handset's, and the ear hears that in decibels. So the score is the
 * worst handset's gain error in dB, and the thing to beat is the uncorrected sketch.
 */
class RoomFitWeightTest {
    private val metresPerUnit = 5.0

    private val weights = listOf(0.01, 0.03, 0.1, 0.3, 1.0, 3.0)

    /** How wrong a hand-drawn room is: bearings fairly good, radii poor. */
    private class Hand(val name: String, val bearingSd: Double, val radiusSd: Double)

    private val hands = listOf(
        Hand("careful", Math.toRadians(10.0), 0.25),
        Hand("sloppy", Math.toRadians(25.0), 0.50)
    )

    /** What one C94 run is worth, per the on-device gate: a pair over 0.30 m of MAD is dropped. */
    private val measurementSd = 0.15

    private fun name(at: Int) = "%016x".format(0x1122334455660000L + at)

    private fun truth(random: Random, handsets: Int): List<RoomIcon> {
        // Spread round the listener at plausible listening distances: one to three metres.
        val bearings = List(handsets) { random.nextDouble(-Math.PI, Math.PI) }
        return bearings.mapIndexed { at, bearing ->
            val radius = random.nextDouble(1.0, 3.0) / metresPerUnit
            RoomIcon(
                name(at),
                (SpatialRoom.CENTRE + radius * sin(bearing)).toFloat(),
                (SpatialRoom.CENTRE - radius * cos(bearing)).toFloat()
            )
        }
    }

    private fun sketchOf(random: Random, room: List<RoomIcon>, hand: Hand): List<RoomIcon> =
        room.map { icon ->
            val dx = icon.x - SpatialRoom.CENTRE
            val dy = SpatialRoom.CENTRE - icon.y
            val bearing = atan2(dx.toDouble(), dy.toDouble()) + random.nextGaussian() * hand.bearingSd
            val radius = hypot(dx.toDouble(), dy.toDouble()) *
                Math.exp(random.nextGaussian() * hand.radiusSd)
            RoomIcon(
                icon.peerId,
                (SpatialRoom.CENTRE + radius * sin(bearing)).toFloat(),
                (SpatialRoom.CENTRE - radius * cos(bearing)).toFloat()
            )
        }

    private fun measuredOf(random: Random, room: List<RoomIcon>): Map<Pair<String, String>, Double> {
        val distances = HashMap<Pair<String, String>, Double>()
        for (one in room.indices) {
            for (two in one + 1 until room.size) {
                val apart = hypot(
                    (room[one].x - room[two].x).toDouble(),
                    (room[one].y - room[two].y).toDouble()
                ) * metresPerUnit
                val read = apart + random.nextGaussian() * measurementSd
                if (read <= 0.0) continue
                distances[room[one].peerId to room[two].peerId] = read
                distances[room[two].peerId to room[one].peerId] = read
            }
        }
        return distances
    }

    /** The worst handset's gain error, in dB, against the room as it really is. */
    private fun worstGainErrorDb(room: List<RoomIcon>, guess: List<RoomIcon>): Double {
        val real = SpatialRoom.layoutOf(room)!!
        val drawn = SpatialRoom.layoutOf(guess)!!
        return room.maxOf {
            abs(20.0 * log10(drawn.distanceGainOf(it.peerId) / real.distanceGainOf(it.peerId)))
        }
    }

    /** The worst handset's bearing error, in degrees, against the room as it really is. */
    private fun worstBearingErrorDegrees(room: List<RoomIcon>, guess: List<RoomIcon>): Double {
        val real = SpatialRoom.layoutOf(room)!!
        val drawn = SpatialRoom.layoutOf(guess)!!
        return room.maxOf {
            var off = drawn.azimuthOf(it.peerId) - real.azimuthOf(it.peerId)
            while (off > Math.PI) off -= 2 * Math.PI
            while (off < -Math.PI) off += 2 * Math.PI
            Math.toDegrees(abs(off))
        }
    }

    /** How far the drawing's bearings were pushed around, in degrees, worst handset. */
    private fun worstBearingShiftDegrees(sketch: List<RoomIcon>, fitted: List<RoomIcon>): Double {
        val after = fitted.associateBy { it.peerId }
        return sketch.maxOf { was ->
            val now = after.getValue(was.peerId)
            val wasBearing = atan2(
                (was.x - SpatialRoom.CENTRE).toDouble(),
                (SpatialRoom.CENTRE - was.y).toDouble()
            )
            val nowBearing = atan2(
                (now.x - SpatialRoom.CENTRE).toDouble(),
                (SpatialRoom.CENTRE - now.y).toDouble()
            )
            var turned = nowBearing - wasBearing
            while (turned > Math.PI) turned -= 2 * Math.PI
            while (turned < -Math.PI) turned += 2 * Math.PI
            Math.toDegrees(abs(turned))
        }
    }

    private fun Random.nextGaussian(): Double {
        // Box-Muller, so the shape of the noise is stated here rather than inherited from java.util.
        val first = nextDouble().coerceAtLeast(1e-12)
        return Math.sqrt(-2.0 * ln(first)) * cos(2.0 * Math.PI * nextDouble())
    }

    private class Tally {
        val scores = ArrayList<Double>()
        var refused = 0
        fun mean() = if (scores.isEmpty()) Double.NaN else scores.average()
        fun ninetieth() =
            if (scores.isEmpty()) Double.NaN else scores.sorted()[(scores.size * 9) / 10]
    }

    private fun sweep(handsets: Int, hand: Hand, rooms: Int): String {
        val sketchScore = Tally()
        val sketchBearing = Tally()
        val fitScore = weights.associateWith { Tally() }
        val fitBearing = weights.associateWith { Tally() }
        val turned = weights.associateWith { Tally() }
        val random = Random(20260911 + handsets)
        repeat(rooms) {
            val room = truth(random, handsets)
            val sketch = sketchOf(random, room, hand)
            val measured = measuredOf(random, room)
            sketchScore.scores.add(worstGainErrorDb(room, sketch))
            sketchBearing.scores.add(worstBearingErrorDegrees(room, sketch))
            for (weight in weights) {
                val fitted = RoomFit.corrected(sketch, measured, weight)
                if (fitted == null) {
                    fitScore.getValue(weight).refused++
                    continue
                }
                fitScore.getValue(weight).scores.add(worstGainErrorDb(room, fitted))
                fitBearing.getValue(weight).scores.add(worstBearingErrorDegrees(room, fitted))
                turned.getValue(weight).scores.add(worstBearingShiftDegrees(sketch, fitted))
            }
        }
        val lines = StringBuilder()
        lines.append(
            "n=$handsets ${hand.name}: sketch alone %.2f dB (90th %.2f), bearing off %.1f deg\n"
                .format(sketchScore.mean(), sketchScore.ninetieth(), sketchBearing.mean())
        )
        for (weight in weights) {
            val score = fitScore.getValue(weight)
            lines.append(
                "    lambda %5.2f  %.2f dB (90th %.2f)  bearing off %.1f deg  turned %.1f deg  refused %d\n".format(
                    weight, score.mean(), score.ninetieth(), fitBearing.getValue(weight).mean(),
                    turned.getValue(weight).mean(), score.refused
                )
            )
        }
        return lines.toString()
    }

    @Test
    fun sweepThePriorWeight() {
        val report = StringBuilder()
        for (handsets in listOf(3, 4, 5)) {
            for (hand in hands) report.append(sweep(handsets, hand, 400))
        }
        println(report)
    }
}
