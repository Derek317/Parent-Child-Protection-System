package app.family.igguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.abs
import kotlin.math.max

// QR pairing views: [QrImageView] shows a code (parent side), [QrScannerView] reads one with the camera (child side).
// Nothing here logs, stores or sends a frame or a decoded text.

internal const val QR_MAX_CHARS = 400
private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

/** A square QR code as ARGB pixels, [size] x [size] (at least the requested size). Black on white whatever the app theme. */
internal class QrPixels(val size: Int, val argb: IntArray)

/** Refuses empty text and text over [QR_MAX_CHARS] characters; the messages never contain the text. */
internal fun qrPixels(text: String, sizePx: Int): QrPixels {
    require(text.isNotEmpty()) { "QR text is empty" }
    require(text.length <= QR_MAX_CHARS) { "QR text is longer than $QR_MAX_CHARS characters" }
    require(sizePx in 1..4096) { "QR size out of range" }
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val size = matrix.width // the writer enlarges a too-small request to the code's own size
    return QrPixels(size, IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) BLACK else WHITE })
}

internal fun qrBitmap(text: String, sizePx: Int): Bitmap {
    val p = qrPixels(text, sizePx)
    return Bitmap.createBitmap(p.argb, p.size, p.size, Bitmap.Config.ARGB_8888)
}

/** Shows one pairing QR. Pass the on-screen pixel size so the bitmap is not rescaled (a blurred QR scans worse). */
class QrImageView(context: Context) : ImageView(context) {
    private var bitmap: Bitmap? = null

    init {
        contentDescription = "配對 QR 碼 / Pairing QR code"
        scaleType = ScaleType.FIT_CENTER
    }

    /** Throws IllegalArgumentException for empty or over-long text and then leaves the current image untouched. */
    fun show(text: String, sizePx: Int) {
        val fresh = qrBitmap(text, sizePx)
        setImageBitmap(fresh)
        bitmap?.recycle()
        bitmap = fresh
    }

    // The secret leaves memory when the view leaves the screen; show() again if it is re-attached.
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
    }
}

enum class ScanError { NO_PERMISSION, NO_CAMERA, CAMERA_IN_USE, CAMERA_FAILED }

/**
 * The first QR text found in a luminance (Y) plane, or null. [rowStride] may exceed [width]; the last row may lack its padding.
 * Never throws for bad input: noise, blank, truncated or inconsistent sizes all give null.
 */
internal fun decodeLuminance(y: ByteArray, width: Int, height: Int, rowStride: Int): String? {
    if (width <= 0 || height <= 0 || rowStride < width) return null
    if (y.size.toLong() < rowStride.toLong() * (height - 1) + width) return null
    return try {
        val source = PlanarYUVLuminanceSource(y, rowStride, height, 0, 0, width, height, false)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
    } catch (_: ReaderException) {
        null
    }
}

/** Maps the preview the TextureView stretched to its bounds back to upright and center-crops it to fill the view. */
internal fun previewMatrix(vw: Float, vh: Float, bw: Float, bh: Float, sensorDegrees: Int, screenDegrees: Int): Matrix {
    val uprightW = if (sensorDegrees % 180 != 0) bh else bw
    val uprightH = if (sensorDegrees % 180 != 0) bw else bh
    val rotationDeg = (360 - screenDegrees) % 360
    val cx = vw / 2f
    val cy = vh / 2f
    val swap = rotationDeg % 180 != 0
    val footW = if (swap) uprightH else uprightW
    val footH = if (swap) uprightW else uprightH
    val fill = max(vw / footW, vh / footH)
    return Matrix().apply {
        postScale(uprightW / vw, uprightH / vh, cx, cy)
        postRotate(rotationDeg.toFloat(), cx, cy)
        postScale(fill, fill, cx, cy)
    }
}

/**
 * Back-camera QR scanner on platform Camera2 only. The caller asks for the CAMERA permission; [start] reports NO_PERMISSION without it.
 * Main thread only: [start], [stop], and both callbacks. [onResult] fires once per [start], then the camera is already stopped.
 */
class QrScannerView(context: Context) : FrameLayout(context) {
    var onResult: (String) -> Unit = {}
    var onError: (ScanError) -> Unit = {}

    private val texture = TextureView(context)
    private val main = Handler(Looper.getMainLooper())
    private var run: Run? = null
    private var delivered = false

    init {
        contentDescription = "掃描 QR 碼的相機畫面 / Camera view for scanning a QR code"
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { run?.let { open(it) } }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { run?.let { applyTransform(it, w, h) } }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { stop(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    /**
     * One start-to-stop camera session. Owns the thread, reader, surface, device and capture session.
     * [closed] is the only gate: every callback re-checks it under the lock and closes what it was handed if it lost the race.
     */
    private class Run(val cameraId: String, val size: Size, val sensorDegrees: Int, val continuousAf: Boolean) {
        val thread = HandlerThread("qr-scan").apply { start() }
        val handler = Handler(thread.looper)
        var closed = false
        var reader: ImageReader? = null
        var surface: Surface? = null
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var lastDecodeMs = 0L // background thread only

        fun release() {
            val s: CameraCaptureSession?; val c: CameraDevice?; val r: ImageReader?; val sf: Surface?
            synchronized(this) {
                if (closed) return
                closed = true
                s = session; c = camera; r = reader; sf = surface
                session = null; camera = null; reader = null; surface = null
            }
            r?.setOnImageAvailableListener(null, null)
            s?.close(); c?.close() // close() on the device blocks until it is shut, so the reader below has no writer left
            r?.close(); sf?.release()
            thread.quitSafely()
        }
    }

    /** Safe in any order and when repeated; a second call while running does nothing. */
    fun start() {
        if (run != null) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { onError(ScanError.NO_PERMISSION); return }
        val picked = try { pickBackCamera() } catch (_: CameraAccessException) { null } catch (_: RuntimeException) { null }
        if (picked == null) { onError(ScanError.CAMERA_FAILED); return }
        val (id, size, sensor, af) = picked
        if (id == null) { onError(ScanError.NO_CAMERA); return }
        delivered = false
        val r = Run(id, size!!, sensor, af)
        run = r
        if (texture.isAvailable) open(r) // otherwise the surface listener opens it
    }

    /** Idempotent. Releases the capture session, camera, reader, surface and thread; later callbacks from them are ignored. */
    fun stop() {
        val r = run ?: return
        run = null
        r.release()
    }

    override fun onDetachedFromWindow() {
        stop()
        main.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    /** Called once per decoded text. Exposed so tests can feed it without a camera. */
    internal fun deliverOnce(text: String) {
        if (delivered) return
        delivered = true
        stop()
        onResult(text)
    }

    private data class Pick(val id: String?, val size: Size?, val sensor: Int, val af: Boolean)

    private fun pickBackCamera(): Pick {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
            val best = sizes?.minByOrNull { abs(it.width * it.height - 1280 * 720) } ?: error("no YUV_420_888 output size")
            val af = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
            return Pick(id, best, ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, af)
        }
        return Pick(null, null, 0, false)
    }

    private fun open(run: Run) {
        if (run !== this.run || run.reader != null) return
        val st = texture.surfaceTexture ?: return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { fail(run, ScanError.NO_PERMISSION); return }
        st.setDefaultBufferSize(run.size.width, run.size.height)
        applyTransform(run, texture.width, texture.height)
        val reader = ImageReader.newInstance(run.size.width, run.size.height, ImageFormat.YUV_420_888, 2)
        val surface = Surface(st)
        synchronized(run) {
            if (run.closed) { reader.close(); surface.release(); return }
            run.reader = reader; run.surface = surface
        }
        reader.setOnImageAvailableListener({ onFrame(run, it) }, run.handler)
        try {
            (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).openCamera(run.cameraId, cameraCallback(run), run.handler)
        } catch (e: CameraAccessException) {
            fail(run, if (e.reason == CameraAccessException.CAMERA_IN_USE) ScanError.CAMERA_IN_USE else ScanError.CAMERA_FAILED)
        } catch (_: SecurityException) {
            fail(run, ScanError.NO_PERMISSION)
        } catch (_: RuntimeException) {
            fail(run, ScanError.CAMERA_FAILED)
        }
    }

    private fun cameraCallback(run: Run) = object : CameraDevice.StateCallback() {
        override fun onOpened(cam: CameraDevice) {
            val targets = synchronized(run) {
                if (run.closed) null else { run.camera = cam; listOf(run.surface, run.reader?.surface) }
            }
            if (targets == null || targets.any { it == null }) { cam.close(); return } // stop() won the race: close what we were handed
            try {
                @Suppress("DEPRECATION") // the SessionConfiguration overload needs API 28; minSdk is 26
                cam.createCaptureSession(targets.filterNotNull(), sessionCallback(run), run.handler)
            } catch (_: CameraAccessException) { fail(run, ScanError.CAMERA_FAILED) } catch (_: RuntimeException) { fail(run, ScanError.CAMERA_FAILED) }
        }
        override fun onDisconnected(cam: CameraDevice) { cam.close(); fail(run, ScanError.CAMERA_IN_USE) }
        override fun onError(cam: CameraDevice, error: Int) {
            cam.close()
            val busy = error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE || error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
            fail(run, if (busy) ScanError.CAMERA_IN_USE else ScanError.CAMERA_FAILED)
        }
    }

    private fun sessionCallback(run: Run) = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
            val targets = synchronized(run) {
                if (run.closed) null else { run.session = s; listOf(run.surface, run.reader?.surface) }
            }
            if (targets == null || targets.any { it == null }) { s.close(); return }
            try {
                val req = s.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                targets.forEach { req.addTarget(it!!) }
                if (run.continuousAf) req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                s.setRepeatingRequest(req.build(), null, run.handler)
            } catch (_: CameraAccessException) { fail(run, ScanError.CAMERA_FAILED) } catch (_: RuntimeException) { fail(run, ScanError.CAMERA_FAILED) }
        }
        override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); fail(run, ScanError.CAMERA_FAILED) }
    }

    // Background thread. acquireLatestImage drops older frames; one decode at a time and at most ~5 per second.
    private fun onFrame(run: Run, reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (_: RuntimeException) { null } ?: return
        try {
            if (run.closed) return
            val now = SystemClock.elapsedRealtime()
            if (now - run.lastDecodeMs < 200) return
            run.lastDecodeMs = now
            val plane = image.planes[0]
            val y = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            val text = decodeLuminance(y, image.width, image.height, plane.rowStride) ?: return
            main.post { if (run === this.run) deliverOnce(text) } // a frame decoded after stop() is dropped here
        } catch (_: RuntimeException) {
            // the reader was closed under us by stop(): nothing to do
        } finally {
            image.close()
        }
    }

    private fun fail(run: Run, error: ScanError) {
        main.post { if (run === this.run) { stop(); onError(error) } }
    }

    private fun applyTransform(run: Run, vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0) return
        val screen = rotationOf(display?.rotation ?: Surface.ROTATION_0)
        texture.setTransform(previewMatrix(vw.toFloat(), vh.toFloat(), run.size.width.toFloat(), run.size.height.toFloat(), run.sensorDegrees, screen))
    }

    private fun rotationOf(r: Int) = when (r) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
}
