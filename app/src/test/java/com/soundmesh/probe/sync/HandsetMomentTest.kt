package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The line that has to survive a night with no cable in it.
 *
 * On 2026-09-14 the room went silent twice and both times the only way to look at the host was to
 * plug it in - which restored the sound, so the fault could never be examined in the state it
 * failed in. The record has to carry the state itself, and it has to carry it whether or not every
 * reading could be taken: a handset that will not answer one question still knows the other eight.
 */
class HandsetMomentTest {
    private val full = HandsetMoment(
        charger = "usb",
        screenOn = true,
        musicActive = false,
        audioMode = "NORMAL",
        musicIndex = 0,
        musicMax = 150,
        musicMuted = true,
        playing = "ALARM:8/15",
        batteryPercent = 90,
        players = "2:1,11"
    )

    @Test
    fun `every reading is on the line, named`() {
        assertEquals(
            "charging=usb screen=on music-active=false mode=NORMAL " +
                "media=0/150 media-muted=yes playing=ALARM:8/15 battery=90% players=2:1,11",
            full.toString()
        )
    }

    /**
     * The one field that separates the fault from somebody pausing their own music.
     *
     * Every other reading is the same in both: zeros arriving from the capture and a handset that
     * says nothing is active on the media stream. An empty list is a player that has gone away.
     */
    @Test
    fun `no players at all is a reading, not a missing one`() {
        assertEquals("players=0:", full.copy(players = "0:").toString().substringAfterLast(' '))
    }

    /**
     * A reading that could not be taken says so rather than defaulting.
     *
     * A zero standing in for "the handset would not say" is the reading this whole line exists to
     * settle, written as the answer it was meant to establish.
     */
    @Test
    fun `a reading nobody could take is a question mark, not a zero`() {
        val partial = HandsetMoment(
            charger = null, screenOn = null, musicActive = null, audioMode = null,
            musicIndex = null, musicMax = null, musicMuted = null, playing = null,
            batteryPercent = null, players = null
        )

        assertEquals(
            "charging=? screen=? music-active=? mode=? " +
                "media=?/? media-muted=? playing=? battery=? players=?",
            partial.toString()
        )
    }

    @Test
    fun `the states that are not the suspect read plainly too`() {
        assertEquals(
            "charging=none screen=off music-active=true mode=IN_CALL " +
                "media=7/15 media-muted=no playing=MEDIA:7/15 battery=18% players=1:1",
            full.copy(
                charger = "none", screenOn = false, musicActive = true, audioMode = "IN_CALL",
                musicIndex = 7, musicMax = 15, musicMuted = false, playing = "MEDIA:7/15",
                batteryPercent = 18, players = "1:1"
            ).toString()
        )
    }
}
