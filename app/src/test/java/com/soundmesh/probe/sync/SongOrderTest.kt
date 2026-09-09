package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which of a folder's files are songs, and what order they play in.
 *
 * Both halves fail quietly. A filter that is too strict leaves a song out of a folder the listener
 * can see it in, and says nothing; one that is too loose puts a cover image into the playlist,
 * where it becomes a refusal in the middle of an evening. The order is worse: any order at all
 * looks like an order, and a folder of an album played in the wrong sequence is only wrong to
 * somebody who knows the album.
 */
class SongOrderTest {
    private fun file(name: String, mimeType: String? = null) = Song("content://f/$name", name, mimeType)

    private fun names(vararg entries: Song) = SongOrder.of(entries.toList()).map { it.name }

    @Test
    fun anEmptyFolderHoldsNoSongs() {
        assertEquals(emptyList<Song>(), SongOrder.of(emptyList()))
    }

    @Test
    fun audioIsKeptAndEverythingElseIsNot() {
        assertEquals(
            listOf("a.mp3"),
            names(file("a.mp3", "audio/mpeg"), file("cover.jpg", "image/jpeg"), file("notes.pdf", "application/pdf"))
        )
    }

    /**
     * .m4a and .mp4 are one container, so a video can arrive wearing an audio extension. It has
     * audio in it and would play; it is still not what anybody picked a folder for. The provider
     * saying no has to beat a hopeful-looking name, and this is the only place it has to.
     */
    @Test
    fun whatTheProviderCallsVideoIsNotASongHoweverItIsNamed() {
        assertEquals(emptyList<String>(), names(file("clip.m4a", "video/mp4"), file("clip.mp4", "video/mp4")))
    }

    /**
     * Providers disagree about what a .flac is: some answer audio/flac, some answer
     * application/octet-stream, some answer nothing at all. Dropping those would hide a song that
     * the listener can see sitting in the folder, which is the worse of the two mistakes.
     */
    @Test
    fun aSongWhoseTypeTheProviderWouldNotSayIsStillASong() {
        assertEquals(
            listOf("b.flac", "c.opus", "d.m4a"),
            names(file("b.flac", "application/octet-stream"), file("c.opus", null), file("d.m4a", ""))
        )
    }

    @Test
    fun anUnknownFileWithNoTypeIsNotASong() {
        assertEquals(emptyList<String>(), names(file("readme", null), file("data.bin", null)))
    }

    /** Ten follows nine. Compared as text it comes second, which is how album order gets lost. */
    @Test
    fun numbersInNamesAreComparedAsNumbers() {
        assertEquals(
            listOf("track2.mp3", "track9.mp3", "track10.mp3"),
            names(file("track10.mp3"), file("track2.mp3"), file("track9.mp3"))
        )
    }

    /** Rippers pad and rippers do not, sometimes in the same folder. */
    @Test
    fun paddingDoesNotChangeANumbersPlace() {
        assertEquals(
            listOf("02 b.mp3", "3 c.mp3", "011 d.mp3"),
            names(file("011 d.mp3"), file("02 b.mp3"), file("3 c.mp3"))
        )
    }

    @Test
    fun caseIsNotWhatOrdersASong() {
        assertEquals(
            listOf("apple.mp3", "Banana.mp3", "cherry.mp3"),
            names(file("Banana.mp3"), file("cherry.mp3"), file("apple.mp3"))
        )
    }

    /**
     * Two names that differ only in case still have to land in one definite order, or a folder
     * plays in a different sequence each time it is opened for no reason anybody could see.
     */
    @Test
    fun twoNamesThatDifferOnlyInCaseStillHaveAnOrder() {
        val once = names(file("song.mp3"), file("SONG.mp3"))
        val again = names(file("SONG.mp3"), file("song.mp3"))
        assertEquals(once, again)
    }

    /** The folder's own order is not an order: SAF hands entries over in whatever it likes. */
    @Test
    fun theOrderIsTheNamesAndNotTheOrderTheyArrivedIn() {
        assertEquals(
            listOf("a.mp3", "b.mp3", "c.mp3"),
            names(file("c.mp3"), file("a.mp3"), file("b.mp3"))
        )
    }

    /**
     * The six names off a real handset, and the order the file manager beside it showed.
     *
     * Compared by code unit these come out 倔强 / 和你一样 / 追梦赤子心 / 闪耀 - stable,
     * reproducible, and an order nobody can predict, because it is where the characters happen to
     * sit in Unicode rather than how they sound. What is fixed here is that rule, not the exact
     * sequence: the collation is the runtime's, and this JVM is not guaranteed to hold the same
     * table as a handset's ICU.
     */
    @Test
    fun chineseNamesReadInPinyinOrderRatherThanCodeUnitOrder() {
        assertEquals(
            listOf("和你一样.mp3", "倔强.mp3", "闪耀.mp3", "追梦赤子心.mp3"),
            names(file("倔强.mp3"), file("闪耀.mp3"), file("追梦赤子心.mp3"), file("和你一样.mp3"))
        )
    }

    /**
     * Latin names come before Chinese ones, and that is not the defect above.
     *
     * Written down because it looks like one twice over. A file named zzz- was once predicted to
     * sort last among Chinese names and did not, which is what surfaced the code-unit ordering -
     * but moving to a collator does not change this half, and should not: Latin script precedes
     * Han in the collation, and it is what the file manager next to the app does. The test exists
     * so the next person to see zzz- at the top does not "fix" it back.
     */
    @Test
    fun aLatinNameComesBeforeTheChineseOnesAndThatIsDeliberate() {
        val ordered = names(file("倔强.mp3"), file("和你一样.mp3"), file("zzz.mp3"))
        assertEquals("zzz.mp3", ordered.first())
    }

    /** A number inside a name is a number wherever it sits, not only at the front. */
    @Test
    fun aNumberLaterInTheNameCountsToo() {
        assertEquals(
            listOf("live disc 1 track 2.mp3", "live disc 1 track 10.mp3", "live disc 2 track 1.mp3"),
            names(
                file("live disc 2 track 1.mp3"),
                file("live disc 1 track 10.mp3"),
                file("live disc 1 track 2.mp3")
            )
        )
    }
}
