package com.soundmesh.product

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two ways a screen says something wrong without anything failing.
 *
 * A label that does not fit is cut off, and the cut is silent: "This phone's audio" became "This
 * phone's a…" on an English build and nothing anywhere knew. A readout whose case was removed
 * falls through to the next one, and the next one here is a sentence that is false - a source
 * dragged out of the ring being described as sitting on it.
 *
 * Read as source because neither runs on the JVM. What is being guarded is a decision in the code
 * rather than a value it computes, which is why there is nothing to call.
 */
class ScreenTextFitTest {
    private fun source(path: String) = File(path).readText(Charsets.UTF_8).replace("\r\n", "\n")

    /**
     * The cells of a segmented control give a long label a second line rather than an ellipsis.
     *
     * Equal width is what makes the row read as one question with N answers, so the control cannot
     * widen to its longest word and the word has to wrap instead. One line was enough for as long
     * as every label was four or five Chinese characters.
     */
    @Test
    fun aLabelTooWideForItsCellWrapsRatherThanBeingCutOff() {
        assertTrue(
            "a segmented label is back to one line, so the longest one is cut off again",
            source("src/main/java/com/soundmesh/product/Look.kt").contains("maxLines = 2,")
        )
    }

    /**
     * A source outside the ring of phones says nothing, and in particular does not say it is on it.
     *
     * The line that used to be here quoted the multiple and the decibels; it was removed on
     * 2026-09-20 as two numbers about a dot somebody is looking at. What it left behind is a case
     * that draws nothing, and a case that draws nothing is the easy one to delete by tidying.
     */
    @Test
    fun aSourcePulledOutOfTheRingIsNotDescribedAsSittingOnIt() {
        assertTrue(
            "the retreated source falls through to another sentence, and that sentence is untrue",
            source("src/main/java/com/soundmesh/product/SpatialPanel.kt")
                .contains("state.retreat > 0f -> Unit")
        )
    }
}
