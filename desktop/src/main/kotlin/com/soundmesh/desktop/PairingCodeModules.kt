package com.soundmesh.desktop

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The code a desktop host shows a handset, as squares - the handset host's PairingCodeImage.matrix,
 * with the same library, version and error correction, so a code from either host is read the same.
 *
 * One entry per module, the standard quiet zone included, `[y][x]` and true for dark: the window
 * scales it to whatever size it draws at, where the handset asks for pixels. Plain arrays so the
 * window needs nothing of the library's.
 */
object PairingCodeModules {
    /** Q, as on the handset: read across a room off a lit screen at an angle. */
    private val HINTS = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q)

    fun of(payload: String): Array<BooleanArray> {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, HINTS)
        return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y) } }
    }
}
