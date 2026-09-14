package com.soundmesh.core

/**
 * What a stream remembers so that one handset can be handed the notes and another the hits.
 *
 * The same place in the design as [Crossover]: one per playing handset, handed in with every chunk,
 * holding what the audio path has heard so far. It is much larger than [Crossover] - two windows of
 * sound and a run of spectra per channel - because deciding whether a sound is being held requires
 * knowing what came before it, and deciding that per sample does not.
 *
 * Two channels, two of everything, for the reason [Crossover] keeps two sets of poles: the left and
 * right of a mix are two different sounds and neither one's history belongs to the other.
 *
 * **What comes out is [held] samples older than what went in**, and the room only stays together
 * because that number is the same on every handset. It is, because [WINDOW] is a constant and the
 * stream is always at one rate. A version of this where the window followed the device would put
 * back exactly the error the rest of this project exists to remove.
 */
class Separation(val size: Int = WINDOW) {
    private val leftHalves = HarmonicPercussive(size)
    private val rightHalves = HarmonicPercussive(size)
    private val left = SlidingSpectrum(size) { re, im -> leftHalves.change(re, im) }
    private val right = SlidingSpectrum(size) { re, im -> rightHalves.change(re, im) }

    private var carrying = false

    /** How far behind the sound coming out is from the sound going in. */
    val held: Int get() = left.held

    /**
     * How much of each half this handset is playing, from this chunk on.
     *
     * Set per chunk rather than ramped across it, like the crossover coefficient and for the same
     * kind of reason: a share here is a property of a whole frame, not of a sample. It does not
     * step, either - neighbouring frames overlap by three quarters of a window and are added
     * through the synthesis window, so a change made between two of them is heard as a crossfade
     * over one window rather than as an edge.
     */
    fun keep(mix: HalvesMix) {
        leftHalves.keep(mix.harmonic, mix.percussive)
        rightHalves.keep(mix.harmonic, mix.percussive)
        carrying = true
    }

    /** This handset's half of the left channel, [held] samples behind what is handed in. */
    fun leftOf(sample: Double): Double = left.pass(sample)

    /** The same for the right channel. Its own history: two channels are two sounds. */
    fun rightOf(sample: Double): Double = right.pass(sample)

    /**
     * Drops what is being carried, for a room that has stopped asking for this split.
     *
     * Needed because this is the one thing in the audio path that is **not** kept warm while it is
     * unused. The filter in [Crossover] runs on every frame whatever the rule says, so a rule
     * arriving finds it already hearing the right thing; a window of transforms is far too
     * expensive to run for nobody. What that costs is a buffer holding a window of whatever was
     * playing when the split was last switched off, which on the way back in would be heard as a
     * fragment of a different part of the song. Cleared, the way back in is silence fading up,
     * which is what a listener would expect from a thing that has to listen before it can answer.
     */
    fun forget() {
        if (!carrying) return
        carrying = false
        left.forget()
        right.forget()
        leftHalves.forget()
        rightHalves.forget()
    }

    companion object {
        /**
         * The window everything here is measured in, in samples.
         *
         * At the one rate this project runs at, 2048 samples is 43 ms of delay and bins 23 Hz
         * apart. Doubling it would halve the bin spacing, which is what tells a held note from
         * its neighbour, and would double the delay. Which of those matters more is a question
         * for a listener, and changing it is changing this number.
         *
         * Whatever it becomes, it stays a **constant**: the delay it causes is only harmless
         * because every handset in the room waits exactly as long as every other one.
         */
        const val WINDOW = 2048
    }
}
