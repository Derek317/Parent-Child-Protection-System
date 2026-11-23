package app.family.igguard

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pinned ZXing core jar is on the classpath and a ~175-character payload survives encode and decode (pure JVM, no camera). */
class QrLibraryTest {
    @Test
    fun longPayloadRoundTripsThroughQrCode() {
        val text = "igguard-pair:" + (0 until 162).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"[it % 64] }.joinToString("")
        assertEquals(175, text.length)
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        // One module per pixel with a quiet zone; scale up so the reader's binarizer has real pixels to work with.
        val scale = 4
        val width = matrix.width * scale
        val height = matrix.height * scale
        val pixels = IntArray(width * height) { i ->
            if (matrix.get(i % width / scale, i / width / scale)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels))))
        assertEquals(text, decoded.text)
    }
}
