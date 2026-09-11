package com.soundmesh.probe.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoredListenerDistanceTest {
    @get:Rule val folder = TemporaryFolder()

    private val peer = "a1b2c3d4e5f60718"
    private val other = "0918273645abcdef"

    private fun store(id: String = peer) = StoredListenerDistance(folder.root, id)

    @Test
    fun aHandsetNoOverheadRoundHasMeasuredHasNoDistance() {
        assertNull(store().read())
    }

    @Test
    fun whatWasWrittenComesBack() {
        store().write(1.75)

        assertEquals(1.75, store().read()!!, 1e-9)
    }

    @Test
    fun theNewestOverheadRoundIsTheOneKept() {
        store().write(3.0)
        store().write(1.25)

        assertEquals(1.25, store().read()!!, 1e-9)
    }

    @Test
    fun anImpossibleDistanceIsNotKept() {
        store().write(2.0)
        store().write(0.0)

        assertEquals(2.0, store().read()!!, 1e-9)
    }

    /**
     * The whole reason this is not kept in the separation file: an overhead round measures the
     * same two names as an ordinary one, and one says where the handset stands while the other
     * says where a person sat. Sharing a name, the second would erase the first.
     */
    @Test
    fun anOverheadRoundDoesNotEraseWhereTheHandsetStands() {
        StoredSeparation(folder.root, peer).write(2.6)

        store().write(1.4)

        assertEquals(2.6, StoredSeparation(folder.root, peer).read()!!, 1e-9)
        assertEquals(1.4, store().read()!!, 1e-9)
    }

    @Test
    fun everyHandsetTheRoundMeasuredComesBackTogether() {
        store().write(1.4)
        store(other).write(2.2)

        assertEquals(mapOf(peer to 1.4, other to 2.2), StoredListenerDistance.all(folder.root))
    }

    /** The room changed and only the person can say so, which is why forgetting is a thing at all. */
    @Test
    fun whatIsForgottenIsGoneRatherThanStale() {
        store().write(1.4)
        store(other).write(2.2)

        store().forget()

        assertEquals(mapOf(other to 2.2), StoredListenerDistance.all(folder.root))
    }

    /** Every other file in the directory is somebody else's fact, including the separations. */
    @Test
    fun readsNothingButItsOwnFiles() {
        StoredSeparation(folder.root, peer).write(2.6)
        File(folder.root, "paired.json").writeText("{}")
        File(folder.root, "${StoredListenerDistance.FILE_PREFIX}not-a-peer").writeText("9.9")

        assertEquals(emptyMap<String, Double>(), StoredListenerDistance.all(folder.root))
    }

    /** The id becomes a file name, and it can arrive off a scanned screen. */
    @Test
    fun aPeerIdThatIsNotOneIsRefusedBeforeItBecomesAPath() {
        val thrown = runCatching { StoredListenerDistance(folder.root, "../elsewhere") }

        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun anUnreadableFileIsNoMeasurement() {
        store().write(1.4)
        File(folder.root, "${StoredListenerDistance.FILE_PREFIX}$peer").writeText("1.4 metres")

        assertNull(store().read())
    }
}
