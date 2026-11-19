package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FeedbackTest {
    /**
     * A space is %20 and never '+': a mail app reads a mailto address as a URI, where '+' is a plus
     * sign, so form encoding would put "SoundMesh+反馈" in the subject.
     */
    @Test
    fun theSubjectAndTheDeviceLineArePercentEncoded() {
        assertEquals(
            "mailto:a@b.com?subject=SoundMesh%20%E5%8F%8D%E9%A6%88" +
                "&body=%0A%0A%0A---%0ASoundMesh%200.4.0%20%C2%B7%20Android%2014",
            feedbackMailto("a@b.com", "SoundMesh 反馈", "SoundMesh 0.4.0 · Android 14")
        )
    }

    /** The two characters that would end the subject early, or start a field nobody wrote. */
    @Test
    fun anAmpersandOrAnEqualsSignStaysInsideItsField() {
        val address = feedbackMailto("a@b.com", "x&body=y", "z")
        assertFalse(address.contains("x&body=y"))
        assertEquals(2, address.split("&").size)
    }
}
