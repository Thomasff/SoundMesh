package com.soundmesh.product

import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SplitAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The one thing on the home screen that nothing else on disk can rebuild: where a person put the
 * phones, and how they set the rule.
 *
 * Every measurement in this app survives being killed - the pair distances, the room field, the
 * listener's distances. The drawing did not, and it is the only one of them that cannot be
 * measured again: it is somebody's opinion about which phone is on which side of the sofa.
 */
class StoredRoomDrawingTest {
    @get:Rule val folder = TemporaryFolder()

    private val one = "a1b2c3d4e5f60718"
    private val two = "0918273645abcdef"
    private val three = "1122334455667788"

    private fun store() = StoredRoomDrawing(folder.root)

    private fun file() = File(folder.root, StoredRoomDrawing.FILE_NAME)

    @Test
    fun saysNothingWhenNobodyHasDrawnAnythingYet() {
        assertNull(store().read())
    }

    @Test
    fun bringsBackWhereEachHandsetWasPut() {
        store().write(
            listOf(RoomIcon(one, 0.2f, 0.3f), RoomIcon(two, 0.75f, 0.5f)),
            RoomState()
        )

        val back = store().read()!!

        assertEquals(
            listOf(RoomIcon(one, 0.2f, 0.3f), RoomIcon(two, 0.75f, 0.5f)),
            back.placements
        )
    }

    @Test
    fun bringsBackTheRuleAsItWasSet() {
        store().write(
            listOf(RoomIcon(one, 0.2f, 0.3f)),
            RoomState(
                mode = SpatialMode.ROTATE,
                pan = -0.4f,
                separation = 0.8f,
                splitAxis = SplitAxis.LOW_HIGH,
                crossoverHz = 1600f,
                envelopment = 0.4f,
                diffusion = 0.75f,
                periodSeconds = 9,
                delayCompensation = false,
                otherHalfIds = setOf(one),
                metresPerUnit = 2.5,
                fitted = true
            )
        )

        val room = store().read()!!.room

        assertEquals(SpatialMode.ROTATE, room.mode)
        assertEquals(-0.4f, room.pan, 1e-6f)
        assertEquals(0.8f, room.separation, 1e-6f)
        assertEquals(SplitAxis.LOW_HIGH, room.splitAxis)
        assertEquals(1600f, room.crossoverHz, 1e-3f)
        assertEquals(0.4f, room.envelopment, 1e-6f)
        assertEquals(0.75f, room.diffusion, 1e-6f)
        assertEquals(9, room.periodSeconds)
        assertEquals(false, room.delayCompensation)
        assertEquals(setOf(one), room.otherHalfIds)
        assertEquals(2.5, room.metresPerUnit, 1e-9)
        assertEquals(true, room.fitted)
    }

    /**
     * Who was in the room is not the drawing, and bringing it back would draw phones that are not
     * there. Every one of these is re-read from something live within a fifth of a second.
     */
    @Test
    fun doesNotBringBackWhoWasInTheRoom() {
        store().write(
            listOf(RoomIcon(one, 0.2f, 0.3f)),
            RoomState(
                icons = listOf(RoomIcon(one, 0.2f, 0.3f)),
                selfId = one,
                silentIds = setOf(one),
                measuredMetres = mapOf((one to two) to 2.0),
                listenerMetres = mapOf(one to 1.5)
            )
        )

        val room = store().read()!!.room

        assertTrue(room.icons.isEmpty())
        assertNull(room.selfId)
        assertTrue(room.silentIds.isEmpty())
        assertTrue(room.measuredMetres.isEmpty())
        assertTrue(room.listenerMetres.isEmpty())
    }

    /**
     * The colours are the exception, and they are kept for the same reason the screen keeps them
     * between sessions: a colour is half of what a handset is called, and two screens showing the
     * same room have to call it the same thing. Nothing assigns one while no session runs - the
     * spatial channel does that, and it is not up - so without this the calibration screen would
     * draw a room of grey phones next to a home screen drawing the same room in colour.
     */
    @Test
    fun bringsBackWhatEachHandsetWasCalled() {
        store().write(
            listOf(RoomIcon(one, 0.2f, 0.3f), RoomIcon(two, 0.6f, 0.4f)),
            RoomState(colours = mapOf(one to 3, two to 7))
        )

        assertEquals(mapOf(one to 3, two to 7), store().read()!!.room.colours)
    }

    /**
     * A line this build cannot read is skipped and the rest of the drawing still comes back.
     *
     * The file is written whole and read whole, so the one that gets cut in half is the last one -
     * and throwing away a room because its final line is short would lose the very drag that was
     * being saved when the phone was killed.
     */
    @Test
    fun keepsTheRestOfADrawingWithALineItCannotRead() {
        store().write(listOf(RoomIcon(one, 0.2f, 0.3f), RoomIcon(two, 0.75f, 0.5f)), RoomState())
        file().appendText("\nat ${three} 0.4")

        val back = store().read()!!

        assertEquals(listOf(one, two), back.placements.map { it.peerId })
    }

    /** And a field written by a later build is skipped rather than taking the drawing with it. */
    @Test
    fun readsADrawingThatSaysThingsThisBuildHasNeverHeardOf() {
        store().write(listOf(RoomIcon(one, 0.2f, 0.3f)), RoomState(separation = 0.6f))
        file().appendText("\ntilt 0.5")

        val back = store().read()!!

        assertEquals(listOf(one), back.placements.map { it.peerId })
        assertEquals(0.6f, back.room.separation, 1e-6f)
    }

    /** An icon off the drawing is not a position anybody could have dragged it to. */
    @Test
    fun refusesAPlaceThatIsNotOnTheDrawing() {
        store().write(listOf(RoomIcon(one, 0.2f, 0.3f)), RoomState())
        file().appendText("\nat ${two} 1.8 0.5\nat ${three} 0.5 NaN")

        assertEquals(listOf(one), store().read()!!.placements.map { it.peerId })
    }

    /**
     * The saved room put back into the live one, which is how the two screens stay the same room.
     *
     * The calibration screen writes the drawing and the home screen reads it back on resuming, so
     * what a person arranged where the measuring happens is what the music then plays. Who is in
     * the room is the live half and is never taken from disk.
     */
    @Test
    fun putsWhatWasSavedBackIntoTheRoomOnScreen() {
        val live = RoomState(
            icons = listOf(RoomIcon(one, 0.5f, 0.5f), RoomIcon(two, 0.5f, 0.5f)),
            selfId = one,
            colours = mapOf(one to 1, two to 2),
            silentIds = setOf(two),
            measuredMetres = mapOf((one to two) to 2.0),
            listenerMetres = mapOf(one to 1.5)
        )
        val saved = SavedDrawing(
            listOf(RoomIcon(one, 0.2f, 0.3f), RoomIcon(three, 0.9f, 0.9f)),
            RoomState(separation = 0.7f, otherHalfIds = setOf(two, three))
        )

        val back = live.readBack(saved)

        // Moved where it was saved; the one nothing was saved about keeps where it was drawn.
        assertEquals(listOf(RoomIcon(one, 0.2f, 0.3f), RoomIcon(two, 0.5f, 0.5f)), back.icons)
        assertEquals(0.7f, back.separation, 1e-6f)
        // The handset that is not in the room does not come back with the part it was carrying.
        assertEquals(setOf(two), back.otherHalfIds)
        assertEquals(one, back.selfId)
        assertEquals(mapOf(one to 1, two to 2), back.colours)
        assertEquals(setOf(two), back.silentIds)
        assertEquals(mapOf((one to two) to 2.0), back.measuredMetres)
        assertEquals(mapOf(one to 1.5), back.listenerMetres)
    }

    /**
     * The colours are the one thing taken from disk only when nothing live has any.
     *
     * The spatial channel hands them out and only runs while something is playing, so between
     * sessions the saved ones are all there is - and the moment a session starts the live ones
     * are the answer, because that is who actually holds which colour.
     */
    @Test
    fun takesTheSavedColoursOnlyWhileNothingIsHandingThemOut() {
        val saved = SavedDrawing(emptyList(), RoomState(colours = mapOf(one to 5)))
        val live = RoomState(icons = listOf(RoomIcon(one, 0.5f, 0.5f)))

        assertEquals(mapOf(one to 5), live.readBack(saved).colours)
        assertEquals(
            mapOf(one to 9),
            live.copy(colours = mapOf(one to 9)).readBack(saved).colours
        )
    }

    /** Written whole and replaced whole: the room as it is now, not merged with the last one. */
    @Test
    fun replacesTheWholeDrawingRatherThanAddingToIt() {
        store().write(listOf(RoomIcon(one, 0.2f, 0.3f), RoomIcon(two, 0.75f, 0.5f)), RoomState())

        store().write(listOf(RoomIcon(three, 0.5f, 0.5f)), RoomState())

        assertEquals(listOf(three), store().read()!!.placements.map { it.peerId })
    }
}
