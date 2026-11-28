package app.family.igguard

import android.Manifest
import android.app.Application
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Random

/** QR image and scanner logic that runs without a camera: encode, luminance decode, scanner state rules, preview transform. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QrViewsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val payload = "igguard-pair:" + (0 until 162).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"[it % 64] }.joinToString("")

    private fun luminance(argb: IntArray): ByteArray = ByteArray(argb.size) { if (argb[it] == 0xFF000000.toInt()) 0 else 0xFF.toByte() }

    /** The same luminance with [pad] extra bytes after every row except the last (as a camera plane may have). */
    private fun withStride(y: ByteArray, width: Int, pad: Int): ByteArray {
        val rows = y.size / width
        val out = ByteArray(width * rows + pad * (rows - 1)) { 0x80.toByte() }
        for (r in 0 until rows) System.arraycopy(y, r * width, out, r * (width + pad), width)
        return out
    }

    @Test fun bitmapOfLongPayloadDecodesAndUsesOnlyBlackAndWhite() {
        assertEquals(175, payload.length)
        val bmp = qrBitmap(payload, 400)
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        assertEquals(setOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), px.toSet())
        assertEquals(payload, decodeLuminance(luminance(px), bmp.width, bmp.height, bmp.width))
    }

    @Test fun bitmapRefusesEmptyOverlongAndBadSize() {
        for (bad in listOf("" to 300, "x".repeat(401) to 300, "ok" to 0)) {
            try { qrBitmap(bad.first, bad.second); fail("accepted ${bad.first.length} chars at ${bad.second}") } catch (_: IllegalArgumentException) {}
        }
        assertNotNull(qrBitmap("x".repeat(400), 600))
    }

    @Test fun decodeWorksWithEqualAndLargerRowStride() {
        val p = qrPixels(payload, 400)
        val y = luminance(p.argb)
        assertEquals(payload, decodeLuminance(y, p.size, p.size, p.size))
        assertEquals(payload, decodeLuminance(withStride(y, p.size, 24), p.size, p.size, p.size + 24))
    }

    @Test fun decodeGivesNullForNoiseBlankTruncatedAndInconsistentInput() {
        val noise = ByteArray(640 * 480).also { Random(7).nextBytes(it) }
        assertNull(decodeLuminance(noise, 640, 480, 640))
        assertNull(decodeLuminance(ByteArray(640 * 480) { 0xFF.toByte() }, 640, 480, 640))
        val p = qrPixels(payload, 400)
        val y = luminance(p.argb)
        assertNull(decodeLuminance(y.copyOf(y.size - 100), p.size, p.size, p.size))
        assertNull(decodeLuminance(ByteArray(0), p.size, p.size, p.size))
        assertNull(decodeLuminance(y, p.size, p.size, p.size - 1)) // stride smaller than width
        assertNull(decodeLuminance(y, 0, p.size, p.size))
    }

    @Test fun scannerWithoutPermissionReportsItAndDoesNotCrash() {
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        val errors = mutableListOf<ScanError>()
        val view = QrScannerView(app).apply { onError = { errors += it } }
        view.start(); view.start(); view.stop(); view.stop()
        assertEquals(listOf(ScanError.NO_PERMISSION, ScanError.NO_PERMISSION), errors)
    }

    @Test fun scannerLifecycleCallsAreSafeInAnyOrder() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val errors = mutableListOf<ScanError>()
        val view = QrScannerView(app).apply { onError = { errors += it } }
        view.stop(); view.stop() // before start
        view.start(); view.start() // Robolectric has no camera: reported, not thrown
        view.stop(); view.stop()
        assertTrue(errors.isNotEmpty() && errors.all { it == ScanError.NO_CAMERA || it == ScanError.CAMERA_FAILED })
    }

    @Test fun decodedTextIsDeliveredOnlyOnce() {
        val got = mutableListOf<String>()
        val view = QrScannerView(app).apply { onResult = { got += it } }
        view.deliverOnce("first"); view.deliverOnce("second")
        view.stop()
        assertEquals(listOf("first"), got)
    }

    @Test fun previewMatrixTurnsLandscapeBufferUprightAndFillsPortraitView() {
        val m = previewMatrix(1080f, 1920f, 1280f, 720f, 90, 0)
        val r = RectF(0f, 0f, 1080f, 1920f).also { m.mapRect(it) }
        assertEquals(0f, r.left, 1f); assertEquals(0f, r.top, 1f); assertEquals(1080f, r.right, 1f); assertEquals(1920f, r.bottom, 1f)
        // A 4:3 buffer cannot fit without cropping: the footprint must cover the view.
        val c = RectF(0f, 0f, 1080f, 1920f).also { previewMatrix(1080f, 1920f, 1280f, 960f, 90, 0).mapRect(it) }
        assertTrue(c.width() >= 1079f && c.height() >= 1919f)
    }

    private fun mapped(m: android.graphics.Matrix, x: Float, y: Float) = floatArrayOf(x, y).also { m.mapPoints(it) }

    private fun assertUprightAndUnsquashed(vw: Float, vh: Float, bw: Float, bh: Float, sensor: Int, screen: Int, upX: Float, upY: Float) {
        val m = previewMatrix(vw, vh, bw, bh, sensor, screen)
        val up = mapped(m, vw / 2f, 0f)
        val right = mapped(m, vw, vh / 2f)
        val cx = vw / 2f; val cy = vh / 2f
        val upLen = Math.hypot((up[0] - cx).toDouble(), (up[1] - cy).toDouble())
        val rightLen = Math.hypot((right[0] - cx).toDouble(), (right[1] - cy).toDouble())
        assertEquals("the camera's up must point to the screen's $upX,$upY", upX.toDouble(), (up[0] - cx) / upLen, 0.01)
        assertEquals("the camera's up must point to the screen's $upX,$upY", upY.toDouble(), (up[1] - cy) / upLen, 0.01)
        val (uprightW, uprightH) = if (sensor % 180 != 0) bh to bw else bw to bh
        assertEquals("the preview keeps the stream's aspect ratio", (uprightH / uprightW).toDouble(), upLen / rightLen, 0.01)
    }

    @Test fun previewIsUprightAndUnsquashedOnAPortraitPhone() {
        assertUprightAndUnsquashed(1080f, 600f, 1280f, 720f, 90, 0, 0f, -1f)
    }

    @Test fun previewIsUprightAndUnsquashedOnTheTabletInPortraitAndLandscape() {
        assertUprightAndUnsquashed(1116f, 525f, 1920f, 1080f, 90, 0, 0f, -1f)
        assertUprightAndUnsquashed(1836f, 525f, 1920f, 1080f, 90, 90, -1f, 0f)
        assertUprightAndUnsquashed(1836f, 525f, 1920f, 1080f, 90, 270, 1f, 0f)
    }
}
