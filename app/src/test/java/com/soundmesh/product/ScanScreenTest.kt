package com.soundmesh.product

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanner's picture, which is the only thing on that screen anybody looks at.
 *
 * Whether it is drawn is a claim about the camera, not a layout preference: the preview keeps
 * whatever frame it was last handed, so leaving it up after the camera has been put down is the
 * screen telling somebody to keep aiming a phone that stopped looking.
 */
class ScanScreenTest {
    @Test
    fun `while it is looking, the camera's picture is on screen`() {
        assertTrue(previewShown(ScanSay.LOOKING))
    }

    /**
     * The one that is easy to get backwards. A stranger's QR code is a thing the camera saw, not
     * a thing that went wrong with it - hiding the preview here would blind the scanner for the
     * rest of the session the first time a takeaway menu wandered into frame.
     */
    @Test
    fun `a code that is not ours leaves the picture up`() {
        assertTrue(previewShown(ScanSay.NOT_OURS))
    }

    /**
     * A scan unbinds the camera, and the three refusals never bound one. In all four there is
     * nothing behind the preview, and a preview with nothing behind it looks exactly like one
     * that is working.
     */
    @Test
    fun `once there is no camera behind it, the picture goes`() {
        assertFalse("a read code puts the camera down", previewShown(ScanSay.SCANNED))
        assertFalse(previewShown(ScanSay.NO_PERMISSION))
        assertFalse(previewShown(ScanSay.NO_CAMERA))
    }
}
