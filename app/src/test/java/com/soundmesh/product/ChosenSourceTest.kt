package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChosenSourceTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun nothingChosenYetReadsAsNothing() {
        assertNull(ChosenSource(folder.root).name())
    }

    @Test
    fun theNameSurvivesBeingWrittenAndReadBack() {
        val chosen = ChosenSource(folder.root)
        chosen.file().writeBytes(ByteArray(16))
        chosen.remember("夜曲.mp3")
        assertEquals("夜曲.mp3", ChosenSource(folder.root).name())
    }

    /**
     * A name with no audio beside it is not a chosen song. The two files are written one after the
     * other, and a process that died between them would otherwise offer a song to play that has
     * nothing behind it.
     */
    @Test
    fun aNameWithoutItsAudioIsNotASong() {
        ChosenSource(folder.root).remember("夜曲.mp3")
        assertNull(ChosenSource(folder.root).name())
    }

    @Test
    fun forgettingRemovesBothTheAudioAndTheName() {
        val chosen = ChosenSource(folder.root)
        chosen.file().writeBytes(ByteArray(16))
        chosen.remember("夜曲.mp3")
        chosen.forget()
        assertNull(chosen.name())
        assertFalse(chosen.file().exists())
    }

    /**
     * The whole reason the copy has a fixed name. A name the service refuses shows up as "choosing
     * worked, playing failed" - two screens apart from the mistake.
     */
    @Test
    fun theCopiedFileNameIsOneTheServiceWillAccept() {
        assertTrue(ChosenSource.SERVICE_ACCEPTS.matches(ChosenSource.FILE_NAME))
    }
}
