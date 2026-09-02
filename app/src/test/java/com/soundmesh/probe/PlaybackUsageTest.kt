package com.soundmesh.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackUsageTest {
    @Test
    fun mapsEachUsageToItsAndroidConstant() {
        assertEquals(1, PlaybackUsage.MEDIA.androidUsage)
        assertEquals(4, PlaybackUsage.ALARM.androidUsage)
        assertEquals(11, PlaybackUsage.ACCESSIBILITY.androidUsage)
    }

    @Test
    fun parsesOnlyTheThreeAllowedUsageNames() {
        assertEquals(PlaybackUsage.ACCESSIBILITY, PlaybackUsage.fromName("ACCESSIBILITY"))
        assertEquals(PlaybackUsage.MEDIA, PlaybackUsage.fromName(null))
        assertThrows(IllegalArgumentException::class.java) { PlaybackUsage.fromName("RINGTONE") }
        assertThrows(IllegalArgumentException::class.java) { PlaybackUsage.fromName("media") }
    }

    @Test
    fun capturesOnlyUseMediaWhileDelayedReplayCarriesItsOwnUsage() {
        val capture = ProbeCase("C1", 20, "com.tencent.qqmusic", ProbeMode.CAPTURE_ONLY)
        assertEquals(PlaybackUsage.MEDIA, capture.playbackUsage)

        val replay = ProbeCase("R2", 20, "com.tencent.qqmusic", ProbeMode.DELAYED_LOCAL_PLAYBACK, PlaybackUsage.ACCESSIBILITY)
        assertEquals(PlaybackUsage.ACCESSIBILITY, replay.playbackUsage)
        assertEquals(ProbeMode.DELAYED_LOCAL_PLAYBACK, replay.mode)
    }
}
