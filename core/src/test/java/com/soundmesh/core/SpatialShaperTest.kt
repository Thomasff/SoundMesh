package com.soundmesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class SpatialShaperTest {
    private val sampleRate = 48000
    private val framesPerChunk = 960

    private fun facingPair() = SpatialLayout(
        listOf(SpatialPosition("left", -1.0, 0.0), SpatialPosition("right", 1.0, 0.0))
    )

    /** A chunk of constant amplitude, so the shaped result reads back as the gain envelope. */
    private fun steady(level: Int, frames: Int = framesPerChunk): ByteArray {
        val pcm = ByteArray(frames * 4)
        for (index in 0 until frames * 2) {
            pcm[index * 2] = (level and 0xFF).toByte()
            pcm[index * 2 + 1] = (level shr 8).toByte()
        }
        return pcm
    }

    private fun sampleAt(pcm: ByteArray, at: Int): Int =
        ((pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()

    private fun leftChannel(pcm: ByteArray): IntArray =
        IntArray(pcm.size / 4) { sampleAt(pcm, it * 4) }

    private fun rightChannel(pcm: ByteArray): IntArray =
        IntArray(pcm.size / 4) { sampleAt(pcm, it * 4 + 2) }

    /** Noise rather than a steady level: an allpass passes a constant through untouched. */
    private fun noise(frames: Int = framesPerChunk): ByteArray {
        val random = java.util.Random(20260914L)
        val pcm = ByteArray(frames * 4)
        for (index in 0 until frames * 2) {
            val level = (random.nextGaussian() * 4000).toInt().coerceIn(-20_000, 20_000)
            pcm[index * 2] = (level and 0xFF).toByte()
            pcm[index * 2 + 1] = (level shr 8).toByte()
        }
        return pcm
    }

    /**
     * A knob at off has to be off - not nearly off. Every room drawn before this field existed
     * decodes with it at zero, and every one of those has to play the bytes it played yesterday.
     */
    @Test
    fun theDiffusionKnobAtZeroLeavesTheChunkByteForByteWhereItWas() {
        val field = SpatialField(SpatialMode.PAN, facingPair(), pan = -1.0, diffusion = 0.0)

        val withFilter = SpatialShaper.shape(
            noise(), field, "left", 0L, sampleRate, diffuse = Decorrelator("left", sampleRate)
        )
        val without = SpatialShaper.shape(noise(), field, "left", 0L, sampleRate)

        assertArrayEquals(without, withFilter)
    }

    /**
     * Refused rather than ignored, on the same terms as the crossover: a rule asking for the
     * handsets to be pulled apart and a handset quietly not doing it sound exactly alike from here,
     * and the one that is wrong is the one nobody would look for.
     */
    @Test(expected = IllegalArgumentException::class)
    fun aRuleThatPullsTheHandsetsApartIsRefusedWithoutAFilterToDoItWith() {
        val field = SpatialField(SpatialMode.PAN, facingPair(), pan = -1.0, diffusion = 1.0)

        SpatialShaper.shape(noise(), field, "left", 0L, sampleRate)
    }

    /**
     * What it is for, and what it costs, in one measurement: the waveform is a different waveform,
     * and the level it comes out at is the headroom an allpass needs and nothing else.
     */
    @Test
    fun diffusionChangesTheWaveformAndTakesOnlyItsHeadroom() {
        val loud = SpatialField(SpatialMode.PAN, facingPair(), pan = -1.0)
        val apart = SpatialField(SpatialMode.PAN, facingPair(), pan = -1.0, diffusion = 1.0)

        // A second, not one chunk: the chain's delay lines add up to about twenty milliseconds,
        // which is a whole chunk, so a chunk-long measurement reads a filter that is still filling
        // and reports energy the filter has not let go of yet as energy it lost.
        val plain = leftChannel(SpatialShaper.shape(noise(sampleRate), loud, "left", 0L, sampleRate))
        val diffused = leftChannel(
            SpatialShaper.shape(noise(sampleRate), apart, "left", 0L, sampleRate, diffuse = Decorrelator("left", sampleRate))
        )

        assertTrue("the waveform came back unchanged", plain.toList() != diffused.toList())
        // Compared over the second half, so the filter's delay lines are full and the level
        // being read is the steady one rather than the fade-in.
        val from = plain.size / 2
        val was = Math.sqrt((from until plain.size).sumOf { plain[it].toDouble() * plain[it] } / (plain.size - from))
        val now = Math.sqrt((from until plain.size).sumOf { diffused[it].toDouble() * diffused[it] } / (plain.size - from))
        assertTrue("no headroom was taken, so a loud master will clip: ${now / was}", now / was < 0.9)
        assertTrue("far more than headroom was taken: ${now / was}", now / was > 0.7)
    }

    @Test
    fun aHandsetTheSourceHasLeftBehindGoesQuiet() {
        val field = SpatialField(SpatialMode.PAN, facingPair(), pan = 1.0)

        val shaped = SpatialShaper.shape(steady(12_000), field, "left", 0L, sampleRate)

        assertArrayEquals(ByteArray(shaped.size), shaped)
    }

    /**
     * What this class interpolates for. A gain held constant across a chunk and stepped at its
     * edge puts a discontinuity into the music every 20 ms, at a fixed 50 Hz. At the default six
     * second period that discontinuity was measured at 1.64% of full scale at its worst, and was
     * not audible to a listener looking for it - so this test does not guard an audible fault
     * today. It guards the shape of the code that keeps the fault proportional to the source's
     * speed, which is the only reason a faster circuit stays safe.
     *
     * So the property is not "the gain moves" but "the join between two chunks is no coarser than
     * the ramp inside one". A stepped implementation has no ramp inside a chunk at all, which
     * makes the second assertion fail and the first one vacuous - both are here.
     */
    @Test
    fun theGainRampsThroughAChunkRatherThanSteppingAtItsEdge() {
        val field = SpatialField(SpatialMode.ROTATE, facingPair())
        val chunkNanos = framesPerChunk * 1_000_000_000L / sampleRate

        val first = leftChannel(SpatialShaper.shape(steady(10_000), field, "left", 0L, sampleRate))
        val next = leftChannel(SpatialShaper.shape(steady(10_000), field, "left", chunkNanos, sampleRate))

        val moved = abs(first.first() - first.last())
        assertTrue("the gain did not move across the chunk at all ($moved)", moved >= 20)
        var inside = 0
        for (frame in 1 until first.size) inside = maxOf(inside, abs(first[frame] - first[frame - 1]))
        val join = abs(next.first() - first.last())
        // Plus one: both sides are rounded to whole 16-bit samples, and the ramp's own step here
        // is a fraction of a count. Without interpolation the join is the whole chunk's change.
        assertTrue("the join steps by $join where the ramp steps by $inside", join <= inside + 1)
    }

    @Test
    fun eachChannelIsScaledOnItsOwn() {
        val field = SpatialField(SpatialMode.SPLIT, facingPair())

        val shaped = SpatialShaper.shape(steady(10_000), field, "right", 0L, sampleRate)

        assertEquals(0, leftChannel(shaped).max())
        assertTrue("the right handset lost its own side", rightChannel(shaped).min() > 9_000)
    }

    /**
     * The chunk handed in is the one the session is holding: on a run with no pending frame
     * adjustment the renderer passes the AudioChunk's own array straight through, and a shaper
     * that wrote into it would leave the room's audio permanently panned wherever it happened to
     * be pointing when the chunk went past.
     */
    @Test
    fun theChunkItWasGivenIsLeftAsItWas() {
        val field = SpatialField(SpatialMode.PAN, facingPair(), pan = 1.0)
        val pcm = steady(12_000)
        val before = pcm.copyOf()

        SpatialShaper.shape(pcm, field, "left", 0L, sampleRate)

        assertArrayEquals(before, pcm)
    }

    /**
     * Whole-room power normalisation can ask for more than unity: one handset carrying a whole
     * side by itself is asked for sqrt(2). A 16-bit sample that overflows does not get louder, it
     * changes sign - the loudest defect available - so it clamps.
     */
    @Test
    fun aGainAboveOneClampsRatherThanWrapping() {
        val solo = SpatialLayout(listOf(SpatialPosition("solo", 1.0, 0.0)))
        val field = SpatialField(SpatialMode.SPLIT, solo)

        val shaped = SpatialShaper.shape(steady(30_000), field, "solo", 0L, sampleRate)

        assertEquals(Short.MAX_VALUE.toInt(), rightChannel(shaped).max())
        assertTrue("a clamp must not wrap", rightChannel(shaped).min() > 0)
    }

    @Test
    fun aChunkThatIsNotWholeStereoFramesIsRefused() {
        val field = SpatialField(SpatialMode.ROTATE, facingPair())

        val thrown = runCatching { SpatialShaper.shape(ByteArray(6), field, "left", 0L, sampleRate) }

        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun anUnknownHandsetIsRefusedRatherThanSilenced() {
        val field = SpatialField(SpatialMode.ROTATE, facingPair())

        val thrown = runCatching { SpatialShaper.shape(steady(10_000), field, "absent", 0L, sampleRate) }

        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }

    /**
     * One handset straight ahead under [SpatialMode.SPLIT] carries both sides equally, and the
     * whole-room normalisation then scales it to exactly unity - so what comes back out is the
     * fold on its own, with no placement gain mixed into the arithmetic being checked.
     */
    private fun soloField(separation: Double, sides: Set<String> = emptySet()) = SpatialField(
        mode = SpatialMode.SPLIT,
        layout = SpatialLayout(listOf(SpatialPosition("solo", 0.0, 1.0))),
        separation = separation,
        otherHalfIds = sides
    )

    /** A chunk whose two channels carry different constants, so a fold is visible in the output. */
    private fun steadyPair(left: Int, right: Int, frames: Int = framesPerChunk): ByteArray {
        val pcm = ByteArray(frames * 4)
        for (frame in 0 until frames) {
            val at = frame * 4
            pcm[at] = (left and 0xFF).toByte()
            pcm[at + 1] = (left shr 8).toByte()
            pcm[at + 2] = (right and 0xFF).toByte()
            pcm[at + 3] = (right shr 8).toByte()
        }
        return pcm
    }

    /** What both channels agree about, on both channels of the handset carrying it. */
    @Test
    fun aHandsetCarryingTheMiddlePlaysWhatTheTwoChannelsShare() {
        val shaped = SpatialShaper.shape(steadyPair(10_000, 4_000), soloField(1.0), "solo", 0L, sampleRate)

        assertEquals(7_000, leftChannel(shaped)[0])
        assertEquals(7_000, rightChannel(shaped)[0])
    }

    /** What they disagree about, and the two channels of it are opposites - that is what a side is. */
    @Test
    fun aHandsetCarryingTheSidesPlaysWhatTheTwoChannelsDisagreeAbout() {
        val field = soloField(1.0, setOf("solo"))

        val shaped = SpatialShaper.shape(steadyPair(10_000, 4_000), field, "solo", 0L, sampleRate)

        assertEquals(3_000, leftChannel(shaped)[0])
        assertEquals(-3_000, rightChannel(shaped)[0])
    }

    /**
     * The limit this effect has to be honest about. Anything panned to the centre lives in both
     * channels identically, so the sides of it are nothing at all - and a mono recording is that
     * case for the whole song. A handset given the sides of mono material is silent, not quiet.
     *
     * This is why the rule is "everything sitting in the middle" and never "the vocal": the kick
     * and the bass usually sit there too, and they leave with it.
     */
    @Test
    fun theSidesOfMaterialTheChannelsAgreeOnAreSilent() {
        val field = soloField(1.0, setOf("solo"))

        val shaped = SpatialShaper.shape(steady(12_000), field, "solo", 0L, sampleRate)

        assertArrayEquals(ByteArray(shaped.size), shaped)
    }

    /**
     * The two parts are a decomposition, not two effects that merely sound different: played
     * together they are the mix that was sent, sample for sample. A rule that failed this would be
     * throwing part of the song away, and nothing else here would notice.
     */
    @Test
    fun theMiddleAndTheSidesAddBackUpToWhatWasSent() {
        val pcm = steadyPair(10_000, 4_000)

        val middle = SpatialShaper.shape(pcm, soloField(1.0), "solo", 0L, sampleRate)
        val sides = SpatialShaper.shape(pcm, soloField(1.0, setOf("solo")), "solo", 0L, sampleRate)

        assertEquals(10_000, leftChannel(middle)[0] + leftChannel(sides)[0])
        assertEquals(4_000, rightChannel(middle)[0] + rightChannel(sides)[0])
    }

    /** The knob at zero has to leave the samples exactly as they were, or it is not a knob. */
    @Test
    fun aKnobAtZeroPassesBothChannelsThrough() {
        val pcm = steadyPair(10_000, 4_000)

        val shaped = SpatialShaper.shape(pcm, soloField(0.0, setOf("solo")), "solo", 0L, sampleRate)

        assertArrayEquals(pcm, shaped)
    }

    /**
     * The knob has the same edge the gain had, and for the same reason. Dragging it publishes a new
     * rule several times a second, and each one moves the fold - so a fold that were held for a whole
     * chunk would step at every chunk edge while a finger is down. The step is worth up to half of the
     * other channel, which is the class of thing a listener already reported hearing once
     * (the gain arriving in one jump, fixed separately), not the class measured as inaudible.
     *
     * Checked as arithmetic rather than as a shape: at the halfway frame the fold must be halfway,
     * which for these two channels is one exact sample value and nothing else.
     */
    @Test
    fun theFoldRampsThroughAChunkRatherThanSteppingAtItsEdge() {
        val pcm = steadyPair(10_000, 4_000)

        val shaped = SpatialShaper.shape(
            pcm, soloField(1.0), "solo", 0L, sampleRate, fromFold = 0.0
        )

        val left = leftChannel(shaped)
        assertEquals("the first frame is where the previous chunk left off", 10_000, left.first())
        // Halfway to a fold of 0.5: 0.75 of its own channel and 0.25 of the other.
        assertEquals("the fold did not move across the chunk", 8_500, left[framesPerChunk / 2])
    }

    /** A handset already under this same rule has nothing to ramp from, and must not invent one. */
    @Test
    fun aFoldThatDidNotChangeIsHeldFlatAcrossTheChunk() {
        val shaped = SpatialShaper.shape(steadyPair(10_000, 4_000), soloField(1.0), "solo", 0L, sampleRate)

        val left = leftChannel(shaped)
        assertEquals(7_000, left.first())
        assertEquals(7_000, left.last())
    }

    /** One handset straight ahead again, so the placement gain is unity and the split is all that shows. */
    private fun spectrumField(separation: Double, high: Set<String> = emptySet()) = SpatialField(
        mode = SpatialMode.SPLIT,
        layout = SpatialLayout(listOf(SpatialPosition("solo", 0.0, 1.0))),
        separation = separation,
        splitAxis = SplitAxis.LOW_HIGH,
        otherHalfIds = high
    )

    /** Full scale flipping sign every sample: the fastest thing this format can carry, on both channels. */
    private fun alternating(level: Int, frames: Int = framesPerChunk): ByteArray {
        val pcm = ByteArray(frames * 4)
        for (frame in 0 until frames) {
            val value = if (frame % 2 == 0) level else -level
            val at = frame * 4
            pcm[at] = (value and 0xFF).toByte()
            pcm[at + 1] = (value shr 8).toByte()
            pcm[at + 2] = (value and 0xFF).toByte()
            pcm[at + 3] = (value shr 8).toByte()
        }
        return pcm
    }

    @Test
    fun aHandsetCarryingTheLowHalfDropsTheFastestWaveThereIs() {
        val shaped = SpatialShaper.shape(
            alternating(10_000), spectrumField(1.0), "solo", 0L, sampleRate, crossover = Crossover()
        )

        val settled = leftChannel(shaped).drop(480)
        assertTrue("nothing that fast is low: ${settled.maxOf { abs(it) }}", settled.all { abs(it) < 200 })
    }

    @Test
    fun aHandsetCarryingTheHighHalfKeepsIt() {
        val shaped = SpatialShaper.shape(
            alternating(10_000), spectrumField(1.0, high = setOf("solo")), "solo", 0L, sampleRate,
            crossover = Crossover()
        )

        val settled = leftChannel(shaped).drop(480)
        assertTrue("all of it should survive: ${settled.minOf { abs(it) }}", settled.all { abs(it) > 9_800 })
    }

    @Test
    fun aLevelThatNeverMovesGoesToTheLowHandsetAndLeavesTheHighOneSilent() {
        val low = SpatialShaper.shape(
            steady(10_000), spectrumField(1.0), "solo", 0L, sampleRate, crossover = Crossover()
        )
        val high = SpatialShaper.shape(
            steady(10_000), spectrumField(1.0, high = setOf("solo")), "solo", 0L, sampleRate,
            crossover = Crossover()
        )

        assertEquals(10_000, leftChannel(low).last().toLong().toInt())
        assertTrue("the high half of a steady level is nothing: ${leftChannel(high).last()}",
            abs(leftChannel(high).last()) < 2)
    }

    /**
     * The property the whole design is built around, checked on real samples rather than on the
     * coefficients: the high half is the mix with the low half subtracted, so whatever the filter
     * does to one is undone by the other. This is what lets the knob wind back to the mix the room
     * was already playing instead of to something that merely resembles it.
     */
    @Test
    fun theTwoSpectrumHalvesAddBackUpToWhatWasSent() {
        val sent = alternating(9_000)
        val low = SpatialShaper.shape(sent, spectrumField(1.0), "solo", 0L, sampleRate, crossover = Crossover())
        val high = SpatialShaper.shape(
            sent, spectrumField(1.0, high = setOf("solo")), "solo", 0L, sampleRate, crossover = Crossover()
        )

        val sum = leftChannel(low).zip(leftChannel(high)) { a, c -> a + c }
        val original = leftChannel(sent)
        for (index in original.indices) {
            assertEquals("frame $index", original[index].toDouble(), sum[index].toDouble(), 1.0)
        }
    }

    /**
     * One axis at a time reaching the samples. A rule that splits by frequency must leave the two
     * channels where they were - if the fold ran as well, the handset carrying the high half would
     * also be carrying the sides, and nobody asked it to.
     */
    @Test
    fun aSplitByFrequencyLeavesTheTwoChannelsWhereTheyWere() {
        val shaped = SpatialShaper.shape(
            steadyPair(10_000, 0), spectrumField(1.0, high = setOf("solo")), "solo", 0L, sampleRate,
            crossover = Crossover()
        )

        assertEquals(0, rightChannel(shaped).last().toLong().toInt())
    }

    /**
     * Refused rather than quietly played flat. A filter that is not there cannot be told apart from
     * a knob at zero by listening, so the failure this catches is a build that separates on screen
     * and plays the same mix from every handset.
     */
    @Test
    fun aSplitByFrequencyWithNoFilterToDoItIsRefused() {
        val thrown = runCatching {
            SpatialShaper.shape(steady(10_000), spectrumField(1.0), "solo", 0L, sampleRate)
        }

        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }

    /**
     * The chunk edge a filter makes. Handed the same silent chunk, a filter that has just heard a
     * loud one is still ringing and a fresh one is not - and if this ever stops being true the room
     * has lost its state between chunks, which is a click fifty times a second.
     */
    @Test
    fun theFilterCarriesOnFromTheChunkBefore() {
        val carried = Crossover()
        SpatialShaper.shape(steady(10_000), spectrumField(1.0), "solo", 0L, sampleRate, crossover = carried)

        val after = SpatialShaper.shape(
            steady(0), spectrumField(1.0), "solo", 0L, sampleRate, crossover = carried
        )
        val cold = SpatialShaper.shape(
            steady(0), spectrumField(1.0), "solo", 0L, sampleRate, crossover = Crossover()
        )

        assertEquals(0, leftChannel(cold).first().toLong().toInt())
        assertTrue("a ringing filter is not a cold one: ${leftChannel(after).first()}",
            leftChannel(after).first() > 5_000)
    }

    /**
     * A rule whose delay moves, handed to a shaper with nowhere to hold the frames, is refused.
     *
     * Refused rather than ignored, on the same terms as a missing crossover and for a sharper
     * reason: a handset silently rendering no wander while the rest of the room renders one is the
     * room disagreeing about what it is playing, which is the one failure this project's whole
     * clock stack exists to prevent, arriving through the one path that never touches a clock.
     */
    @Test(expected = IllegalArgumentException::class)
    fun aRuleThatMovesInTimeNeedsSomewhereToHoldFrames() {
        val room = SpatialField(
            SpatialMode.SPLIT, facingPair(), shimmerDelayNanos = 4_000_000L
        )
        SpatialShaper.shape(noise(), room, "left", 0L, sampleRate)
    }

    /**
     * And a line that is present while both knobs are down changes not one sample.
     *
     * The case every session lands in the moment somebody turns either feature off again - the
     * line is kept and fed rather than dropped, so "off" has to be the identity through it rather
     * than merely close to it. Asserted on the bytes, because a delay line that was very slightly
     * lossy would pass every listening test and quietly colour every room that had ever tried the
     * feature once.
     */
    @Test
    fun aDelayLineAtRestPassesTheChunkThroughUntouched() {
        val room = SpatialField(SpatialMode.SPLIT, facingPair())
        val pcm = noise()
        val plain = SpatialShaper.shape(pcm, room, "left", 0L, sampleRate)
        val through = SpatialShaper.shape(
            pcm, room, "left", 0L, sampleRate, travel = TravellingDelay(sampleRate)
        )
        assertArrayEquals(plain, through)
    }

    /**
     * The commonest case in the whole file, and the one a filter is most likely to spoil quietly.
     * A source nobody has moved has to come out byte for byte what it came in as, or every chunk of
     * every song is being filtered slightly for ever with nothing on any screen saying so.
     */
    @Test
    fun aRoomWithItsSourceWhereItStandsPlaysExactlyWhatItAlwaysPlayed() {
        val room = SpatialField(SpatialMode.PAN, facingPair(), pan = 0.0)
        val pcm = noise()

        val plain = SpatialShaper.shape(pcm, room, "left", 0L, sampleRate)
        val shelved = SpatialShaper.shape(pcm, room, "left", 0L, sampleRate, shelf = DistanceShelf())

        assertArrayEquals(plain, shelved)
    }

    /**
     * And the point of it. A source dragged away loses more of its top than of its bottom, because
     * what reaches a listener from across a room has mostly been off a wall and walls keep the
     * bottom - see [DistanceShelf].
     *
     * Measured as a ratio of ratios so that the level the retreat already takes off divides out:
     * what is being asked is whether the **top** went further down than the whole did, which is the
     * only part of this the shelf is responsible for.
     */
    @Test
    fun aSourceDraggedAwayLosesMoreOfItsTopThanOfItsWhole() {
        val here = SpatialField(SpatialMode.PAN, facingPair(), pan = 0.0)
        val away = here.copy(retreat = 1.0)
        // Alternating samples are the top of what this rate can carry; a steady level is the bottom.
        val treble = steadyAlternating(8000)
        val bass = steady(8000)

        val trebleRatio = energyOf(
            SpatialShaper.shape(treble, away, "left", 0L, sampleRate, shelf = DistanceShelf())
        ) / energyOf(SpatialShaper.shape(treble, here, "left", 0L, sampleRate, shelf = DistanceShelf()))
        val bassRatio = energyOf(
            SpatialShaper.shape(bass, away, "left", 0L, sampleRate, shelf = DistanceShelf())
        ) / energyOf(SpatialShaper.shape(bass, here, "left", 0L, sampleRate, shelf = DistanceShelf()))

        assertTrue("the top did not go further down: $trebleRatio against $bassRatio", trebleRatio < bassRatio)
        // The bottom is the retreat's business alone: down by exactly the level the readout
        // claims and not one decibel more. Written out here rather than asked of the rule, so a
        // shelf leaning on the bottom could not hide behind the thing it was leaning on.
        val levelOnly = 10.0.pow(-SpatialField.RETREAT_DECIBELS / 20.0).let { it * it }
        // Loose by the width of a sixteen bit sample and no looser: the shaper writes shorts,
        // so the ratio of two rounded chunks cannot land on the real number exactly.
        assertEquals(levelOnly, bassRatio, 1e-5)
    }

    /**
     * A rule that moves the source needs somewhere for the filter to keep what it has heard, and
     * says so rather than playing an undulled room. A missing filter and a source that has not
     * moved sound exactly alike, which is the pair this refusal exists to tell apart - the same
     * argument the crossover's own requirement is written on.
     */
    @Test
    fun aRoomThatHasMovedItsSourceRefusesToPlayWithoutSomewhereToFilter() {
        val away = SpatialField(SpatialMode.PAN, facingPair(), pan = 0.0, retreat = 1.0)

        val thrown = runCatching {
            SpatialShaper.shape(steady(8000), away, "left", 0L, sampleRate)
        }.exceptionOrNull()

        assertTrue("played anyway: $thrown", thrown is IllegalArgumentException)
    }

    /** The top of what this rate can carry, as a chunk. */
    private fun steadyAlternating(level: Int, frames: Int = framesPerChunk): ByteArray {
        val pcm = ByteArray(frames * 4)
        for (index in 0 until frames * 2) {
            val value = if ((index / 2) % 2 == 0) level else -level
            pcm[index * 2] = (value and 0xFF).toByte()
            pcm[index * 2 + 1] = (value shr 8).toByte()
        }
        return pcm
    }

    /** Sum of squares on the left channel, skipping the first frames while the filter settles. */
    private fun energyOf(pcm: ByteArray): Double =
        leftChannel(pcm).drop(200).sumOf { it.toDouble() * it.toDouble() }
}
