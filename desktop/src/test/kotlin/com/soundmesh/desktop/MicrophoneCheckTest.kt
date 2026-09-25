package com.soundmesh.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A microphone that opens and hears nothing.
 *
 * The numbers are this laptop's, 2026-09-26: with F4 down the endpoint read muted and two seconds
 * came back as 96000 samples of exactly zero, none of them flagged silent; with it up the quietest
 * tenth of a second still peaked at 513 of 32767. So "every sample is zero" is not a threshold that
 * needs tuning - a live microphone does not produce it.
 */
class MicrophoneCheckTest {
    private val room = ShortArray(24_000) { if (it % 2 == 0) 513 else -498 }
    private val zeros = ShortArray(24_000)

    @Test
    fun aMutedEndpointIsSilencedWhateverItRecorded() {
        assertTrue(MicrophoneCheck.silenced(1f to true, room))
    }

    @Test
    fun anInputLevelOfZeroIsSilenced() {
        assertTrue(MicrophoneCheck.silenced(0f to false, room))
    }

    /** Muted somewhere nothing reports - a driver, a privacy switch that hands back zeros. */
    @Test
    fun aRecordingOfNothingButZerosIsSilencedEvenWhenTheEndpointLooksFine() {
        assertTrue(MicrophoneCheck.silenced(1f to false, zeros))
        assertTrue(MicrophoneCheck.silenced(null, zeros))
    }

    @Test
    fun aLiveMicrophoneIsNot() {
        assertFalse(MicrophoneCheck.silenced(1f to false, room))
        assertFalse(MicrophoneCheck.silenced(0.05f to false, room))
        assertFalse(MicrophoneCheck.silenced(null, room))
    }

    /** Nothing recorded is not a recording of silence, and blocking a round on it would be a guess. */
    @Test
    fun noSamplesAtAllIsNotTakenAsSilence() {
        assertFalse(MicrophoneCheck.silenced(1f to false, ShortArray(0)))
    }
}
