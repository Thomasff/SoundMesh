package com.soundmesh.probe.sync

import com.google.zxing.common.BitMatrix
import com.soundmesh.core.PairingCode
import com.soundmesh.core.PairingCodeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrReaderTest {
    private val payload =
        PairingCodeCodec.encode(PairingCode("da3fe1c00de55dc6", "192.168.43.1", 45124))

    /**
     * A camera plane, with the two things that make one different from a plain image: rows padded
     * out to a stride, and a last row that stops at the end of the pixels rather than at the end of
     * the stride. Padding is filled with a value that is neither black nor white, so a reader that
     * kept it would see garbage rather than something that happens to decode anyway.
     */
    private fun plane(matrix: BitMatrix, rowStride: Int, pixelStride: Int): ByteArray {
        val bytes = ByteArray(rowStride * (matrix.height - 1) + matrix.width * pixelStride)
        bytes.fill(0x7f)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                val value = if (matrix.get(x, y)) 0 else 0xff
                bytes[y * rowStride + x * pixelStride] = value.toByte()
            }
        }
        return bytes
    }

    @Test
    fun readsACodeOutOfAnUnpaddedFrame() {
        val matrix = PairingCodeImage.matrix(payload, 240)
        val frame = plane(matrix, matrix.width, 1)
        val luminance = QrReader.luminance(frame, matrix.width, 1, matrix.width, matrix.height)
        assertEquals(payload, QrReader.read(luminance, matrix.width, matrix.height))
    }

    @Test
    fun readsACodeOutOfAFrameWhoseRowsArePadded() {
        val matrix = PairingCodeImage.matrix(payload, 240)
        val stride = matrix.width + 37
        val frame = plane(matrix, stride, 1)
        val luminance = QrReader.luminance(frame, stride, 1, matrix.width, matrix.height)
        assertEquals(payload, QrReader.read(luminance, matrix.width, matrix.height))
    }

    @Test
    fun readsACodeOutOfAFrameWhoseLuminanceIsInterleaved() {
        val matrix = PairingCodeImage.matrix(payload, 240)
        val stride = matrix.width * 2 + 8
        val frame = plane(matrix, stride, 2)
        val luminance = QrReader.luminance(frame, stride, 2, matrix.width, matrix.height)
        assertEquals(payload, QrReader.read(luminance, matrix.width, matrix.height))
    }

    @Test
    fun findsNothingWhenThePaddingIsKept() {
        // The reason the test above is not a tautology: read the same bytes as if the stride were
        // the width and nothing comes back, so it is the compaction that makes the code readable.
        val matrix = PairingCodeImage.matrix(payload, 240)
        val stride = matrix.width + 37
        val frame = plane(matrix, stride, 1)
        assertNull(QrReader.read(frame.copyOf(matrix.width * matrix.height), matrix.width, matrix.height))
    }

    @Test
    fun findsNothingInAFrameWithNoCodeInIt() {
        assertNull(QrReader.read(ByteArray(240 * 240), 240, 240))
    }
}
