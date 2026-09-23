package com.soundmesh.desktop

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.soundmesh.core.PairingCode
import com.soundmesh.core.PairingCodeCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingCodeModulesTest {
    /**
     * What the window draws reads back as the code it was given - read the way a camera would,
     * off pixels, rather than trusted because the encoder said so.
     */
    @Test
    fun theDrawnCodeReadsBackAsThePayload() {
        val payload = PairingCodeCodec.encode(PairingCode("0123456789abcdef", "192.168.0.150", 45123))
        val modules = PairingCodeModules.of(payload)
        val scale = 4
        val side = modules.size * scale
        val pixels = IntArray(side * side) { i ->
            val x = i % side / scale
            val y = i / side / scale
            if (modules[y][x]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels))))
        assertEquals(payload, read.text)
    }
}
