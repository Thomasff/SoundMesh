package com.soundmesh.probe.sync

import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Turning one camera frame into the text a code carries.
 *
 * Pure ZXing on purpose, the same split [PairingCodeImage] makes: nothing here touches Android, so
 * what the scanner will read can be exercised against a code this build itself drew rather than
 * only against a camera. What the text then means is not decided here - the caller hands it to
 * [com.soundmesh.core.PairingCodeCodec], which is where a stranger's bytes are checked.
 */
object QrReader {
    /**
     * The Y plane of a camera frame, without the shape the camera gave it.
     *
     * A camera buffer is not an image: rows are padded out to a stride the hardware likes, and on
     * some devices the luminance samples are interleaved rather than adjacent. Neither is visible
     * in the width and height, so a reader handed the raw buffer sees the image sheared by however
     * much padding there was. The last row is read defensively because a plane usually ends at the
     * end of its pixels rather than at the end of its stride.
     */
    fun luminance(plane: ByteArray, rowStride: Int, pixelStride: Int, width: Int, height: Int): ByteArray {
        val compact = ByteArray(width * height)
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) {
                val index = row + x * pixelStride
                if (index < plane.size) compact[y * width + x] = plane[index]
            }
        }
        return compact
    }

    /** The text of the one QR code in this frame, or null - most frames have none. */
    fun read(luminance: ByteArray, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        // A fresh reader per frame rather than one kept around: ZXing's readers carry state between
        // decodes, and a frame is far more expensive than the allocation.
        return runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
    }
}
