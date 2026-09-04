package com.soundmesh.probe.sync

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Drawing the code a host shows.
 *
 * Split in two on purpose: [matrix] is the encoding and touches nothing but ZXing, so what a host
 * puts on its screen can be decoded back in a unit test rather than only off a camera. [bitmap] is
 * the part that needs a screen and carries no decisions.
 */
object PairingCodeImage {
    /**
     * Error correction at Q rather than the default M. The code is read across a room off a lit
     * screen at an angle, which is where the redundancy is spent; the payload is short enough that
     * the extra costs nothing that matters.
     */
    private val HINTS = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q)

    /** Big enough to be read across a room, small enough to leave a status line under it. */
    const val DEFAULT_PIXELS = 720

    fun matrix(payload: String, size: Int): BitMatrix =
        QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, HINTS)

    fun bitmap(payload: String, size: Int): Bitmap {
        val matrix = matrix(payload, size)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
}
