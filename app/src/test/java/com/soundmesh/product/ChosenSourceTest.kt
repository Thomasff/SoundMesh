package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChosenSourceTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun nothingChosenYetReadsAsNothing() {
        assertNull(ChosenSource(folder.root).chosen())
    }

    @Test
    fun whatWasChosenSurvivesBeingWrittenAndReadBack() {
        ChosenSource(folder.root).remember("content://media/external/audio/91", "夜曲.mp3")
        assertEquals(
            Chosen("content://media/external/audio/91", "夜曲.mp3"),
            ChosenSource(folder.root).chosen()
        )
    }

    /**
     * One file rather than two, and this is why: a name and an address written one after the other
     * can be torn apart by a process that dies between them, and a name with nothing behind it
     * offers the listener a song that will not play - a failure two screens away from its cause.
     * Together in one file, half a write does not parse and reads as nothing chosen.
     */
    @Test
    fun halfOfARecordIsNothingChosen() {
        File(folder.root, ChosenSource.CHOSEN_FILE).writeText("content://media/external/audio/91")
        assertNull(ChosenSource(folder.root).chosen())
    }

    @Test
    fun anEmptyRecordIsNothingChosen() {
        File(folder.root, ChosenSource.CHOSEN_FILE).writeText("")
        assertNull(ChosenSource(folder.root).chosen())
    }

    /** Providers are free to hand back names people typed, and people type all sorts of things. */
    @Test
    fun aNameWithALineBreakInItComesBackWhole() {
        ChosenSource(folder.root).remember("content://f/1", "live\nat home.mp3")
        assertEquals("live\nat home.mp3", ChosenSource(folder.root).chosen()?.name)
    }

    @Test
    fun forgettingLeavesNothingChosen() {
        val chosen = ChosenSource(folder.root)
        chosen.remember("content://f/1", "夜曲.mp3")
        chosen.forget()
        assertNull(chosen.chosen())
    }

    /**
     * Until this song was played from its own address, choosing one copied the whole file into
     * this app's directory. An install that upgrades across that change is holding a copy of
     * somebody's music that nothing will ever read again, and it is as big as the song was.
     */
    @Test
    fun theCopyTheOldWayLeftBehindIsCleanedUp() {
        val legacy = File(folder.root, ChosenSource.LEGACY_FILE_NAME)
        legacy.writeBytes(ByteArray(16))
        File(folder.root, ChosenSource.LEGACY_NAME_FILE).writeText("夜曲.mp3")
        ChosenSource(folder.root).discardTheOldCopy()
        assertFalse(legacy.exists())
        assertFalse(File(folder.root, ChosenSource.LEGACY_NAME_FILE).exists())
    }

    @Test
    fun cleaningUpAnInstallThatNeverHadOneIsFine() {
        ChosenSource(folder.root).discardTheOldCopy()
        assertTrue(folder.root.isDirectory)
    }

    /**
     * A song whose permission this app no longer holds is not a song it can offer.
     *
     * Permissions outlive a process but not everything: the listener can revoke them, and a
     * provider that was never asked for a persistable grant hands one that dies with the process.
     * Offering the song anyway puts the refusal at the moment somebody presses play, which is a
     * screen away from anything that could explain it - and the song is still sitting there in
     * their file manager, which makes it look like this app is broken rather than unpermitted.
     */
    @Test
    fun aSongThisAppMayNoLongerOpenIsNotOnOffer() {
        val chosen = Chosen("content://f/1", "夜曲.mp3")
        assertNull(stillPermitted(chosen, listOf("content://f/2")))
        assertNull(stillPermitted(chosen, emptyList()))
    }

    @Test
    fun aSongThisAppStillHoldsIsOnOffer() {
        val chosen = Chosen("content://f/1", "夜曲.mp3")
        assertEquals(chosen, stillPermitted(chosen, listOf("content://f/2", "content://f/1")))
    }

    @Test
    fun nothingChosenStaysNothingHoweverManyPermissionsAreHeld() {
        assertNull(stillPermitted(null, listOf("content://f/1")))
    }

    /** And it does not take the new record with it. */
    @Test
    fun cleaningUpDoesNotForgetWhatIsChosenNow() {
        val chosen = ChosenSource(folder.root)
        chosen.remember("content://f/1", "夜曲.mp3")
        chosen.discardTheOldCopy()
        assertEquals("夜曲.mp3", chosen.chosen()?.name)
    }
}
