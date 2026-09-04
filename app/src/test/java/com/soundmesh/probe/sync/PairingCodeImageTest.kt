package com.soundmesh.probe.sync

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.soundmesh.core.PairingCode
import com.soundmesh.core.PairingCodeCodec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the host draws, read back by the same decoder the scanner will use.
 *
 * This is the half of the pairing path that can be proved without a camera: a code whose payload
 * does not survive its own encoder would otherwise only fail once someone is holding two phones.
 */
class PairingCodeImageTest {
    private val code = PairingCode(hostId = "0123456789abcdef", address = "192.168.43.1", chunkPort = 45124)

    /** ZXing decodes from luminance, and a matrix is already black and white. */
    private class MatrixLuminance(private val matrix: BitMatrix) :
        LuminanceSource(matrix.width, matrix.height) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row != null && row.size >= width) row else ByteArray(width)
            for (x in 0 until width) out[x] = if (matrix.get(x, y)) 0 else 255.toByte()
            return out
        }

        override fun getMatrix(): ByteArray {
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[y * width + x] = if (matrix.get(x, y)) 0 else 255.toByte()
                }
            }
            return out
        }
    }

    private fun decode(matrix: BitMatrix): String =
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(MatrixLuminance(matrix)))).text

    @Test
    fun theCodeAHostShowsIsTheCodeAScannerReads() {
        val payload = PairingCodeCodec.encode(code)

        val read = decode(PairingCodeImage.matrix(payload, 512))

        assertEquals(payload, read)
        assertEquals(code, PairingCodeCodec.decode(read))
    }

    /** Whatever the screen gives it, the payload has to come back the same. */
    @Test
    fun survivesTheSizesAScreenMightGiveIt() {
        val payload = PairingCodeCodec.encode(code)

        listOf(256, 384, 720).forEach { size ->
            val matrix = PairingCodeImage.matrix(payload, size)
            assertEquals(size, matrix.width)
            assertEquals("size $size", payload, decode(matrix))
        }
    }

    /** An IPv6 host address is the longest payload this has to carry. */
    @Test
    fun carriesTheLongestAddressItCouldBeGiven() {
        val payload = PairingCodeCodec.encode(code.copy(address = "fe80:0000:0000:0000:0202:b3ff:fe1e:8329"))

        assertEquals(payload, decode(PairingCodeImage.matrix(payload, 512)))
    }
}
