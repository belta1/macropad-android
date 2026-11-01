package dev.belta.macropad.gesture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import dev.belta.macropad.model.Gestures

/**
 * Watches the front camera on its own thread: hand poses (MediaPipe gesture recognizer) and
 * whether a face is in view. Frames are only kept in memory, one at a time.
 *
 * Poses, swipes and pinch and drag come from [HandTracker]. "away" fires after no face or hand was
 * seen for [awayMs], "back" when one is seen again. Frames are turned upright and mirrored (as in
 * a mirror: your right hand on the right), which is also what MediaPipe expects for left / right.
 * Callbacks come on the camera thread. An engine runs once: after [stop], make a new one. All
 * engines share one thread, so a new engine opens the camera only after the old one closed it.
 * Camera and model errors end up in [Listener.onError], never as a crash of the app.
 */
class GestureEngine(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** A pose, swipe or [Gestures.AWAY] / [Gestures.BACK]; [left] = made with the left hand. */
        fun onGesture(id: String, left: Boolean)
        /** One step of pinch and drag. */
        fun onDrag(up: Boolean)
        fun onError(message: String)
    }

    @Volatile var awayMs = 3 * 60_000L
    /** How far the screen is turned (0, 90, 180, 270), so frames can be turned upright. */
    @Volatile var displayRotation = 0

    private var started = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var recognizer: GestureRecognizer? = null
    private var faces: FaceDetector? = null
    private var sensorOrientation = 270

    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    private var luma = ByteArray(0)
    private var chromaU = ByteArray(0)
    private var chromaV = ByteArray(0)

    private var lastFrame = 0L
    private var lastFaceCheck = 0L
    private var lastSeen = 0L
    private var present = true
    private val tracker = HandTracker()

    // Diagnostics: frames handled per second and time per frame.
    private var statFrames = 0
    private var statSince = 0L
    private var statWorkMs = 0L
    private var statConvertMs = 0L
    private var statHandMs = 0L
    private var statFaces = 0
    private var statFaceChecks = 0
    private var statHands = 0
    @Volatile var status = "off"
        private set

    /** Set by [stop]; callbacks that arrive later (the camera opening, a frame) only clean up. */
    @Volatile private var stopped = false

    val running get() = started && !stopped

    fun start() {
        if (started || stopped) return
        started = true
        status = "starting"
        handler.post { safely { open() } }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        status = "off"
        if (!started) return
        handler.post {
            runCatching { session?.close() }; session = null
            runCatching { camera?.close() }; camera = null
            runCatching { reader?.close() }; reader = null
            runCatching { recognizer?.close() }; recognizer = null
            runCatching { faces?.close() }; faces = null
        }
    }

    /** Runs camera-thread work; an error stops the engine instead of crashing the app. */
    private inline fun safely(work: () -> Unit) {
        if (stopped) return
        try {
            work()
        } catch (e: Exception) {
            stop()
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    @SuppressLint("MissingPermission") // the activity asks for the camera before starting
    private fun open() {
        try {
            recognizer = GestureRecognizer.createFromOptions(context, GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.6f)
                .setMinHandPresenceConfidence(0.6f)
                .setMinTrackingConfidence(0.5f)
                .build())
            faces = FaceDetector.createFromOptions(context, FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_detector.tflite").build())
                .setRunningMode(RunningMode.VIDEO)
                .setMinDetectionConfidence(0.5f)
                .build())
        } catch (e: Exception) {
            return fail("Gesture models could not load: ${e.message}")
        }
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return fail("This tablet has no front camera")
        val traits = manager.getCameraCharacteristics(id)
        sensorOrientation = traits.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        val sizes = traits.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        // The models look at ~200 px images, so the smallest size of at least 320 × 240 is plenty.
        val size = sizes.filter { it.width >= 320 && it.height >= 240 }.minByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height } ?: return fail("The front camera has no usable size")
        val fps = traits.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            .filter { it.upper >= 15 }.minByOrNull { it.upper }
        reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ r -> safely { r.acquireLatestImage()?.use { onFrame(it) } } }, handler)
        }
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (stopped) return device.close()
                camera = device
                safely { startSession(device, fps) }
            }
            override fun onDisconnected(device: CameraDevice) {
                device.close()
                if (camera === device && !stopped) { stop(); fail("Another app is using the camera") }
            }
            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                if (!stopped) { stop(); fail("The camera stopped (error $error)") }
            }
        }, handler)
        Log.i(TAG, "front camera $id, ${size.width}×${size.height}, sensor at $sensorOrientation°, fps $fps")
    }

    @Suppress("DEPRECATION") // the session API that takes a plain list of surfaces; Android 9 has nothing newer worth it
    private fun startSession(device: CameraDevice, fps: Range<Int>?) {
        val surface = reader?.surface ?: return
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) = safely {
                session = s
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                }.build()
                s.setRepeatingRequest(request, null, handler)
                lastSeen = SystemClock.uptimeMillis()
                statSince = lastSeen
                status = "watching"
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (!stopped) { stop(); fail("The camera could not start") }
            }
        }, handler)
    }

    private fun onFrame(image: Image) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFrame < FRAME_MS) return
        lastFrame = now
        val recognizer = recognizer ?: return
        // Nobody in view: only look for a face, once a second; the hand model is the costly part.
        if (!present && now - lastFaceCheck < FACE_MS) return
        // Front camera: turn by the sensor's mounting plus the screen's rotation.
        val upright = toBitmap(image, (sensorOrientation + displayRotation) % 360)
        val mpImage = BitmapImageBuilder(upright).build()
        val converted = SystemClock.uptimeMillis()

        var hand = false
        if (present) {
            val result = recognizer.recognizeForVideo(mpImage, now)
            val points = result.landmarks().firstOrNull()
            hand = points != null
            val aspect = upright.width.toFloat() / upright.height
            val found = points?.let { list ->
                val top = result.gestures().firstOrNull()?.firstOrNull()
                Hand(
                    list.map { Point(it.x() * aspect, it.y()) },
                    left = result.handedness().firstOrNull()?.firstOrNull()?.categoryName() == "Left",
                    builtIn = top?.categoryName()?.takeIf { it != "None" && top.score() >= MIN_SCORE },
                )
            }
            for (event in tracker.update(now, found).onEach { Log.i(TAG, "fired $it") }) when (event) {
                is HandTracker.Event.Pose -> listener.onGesture(event.id, event.left)
                is HandTracker.Event.Swipe -> listener.onGesture(event.id, false)
                is HandTracker.Event.Drag -> listener.onDrag(event.up)
            }
        }
        val recognized = SystemClock.uptimeMillis()

        var seen = hand
        if (!seen && now - lastFaceCheck >= FACE_MS) {
            lastFaceCheck = now
            seen = faces?.detectForVideo(mpImage, now)?.detections()?.isNotEmpty() == true
            statFaceChecks++
            if (seen) statFaces++
        }
        if (hand) statHands++
        onPresence(seen, now)

        statFrames++
        statWorkMs += SystemClock.uptimeMillis() - now
        statConvertMs += converted - now
        statHandMs += recognized - converted
        if (now - statSince >= 5_000) {
            val frames = statFrames.coerceAtLeast(1)
            status = "watching · %.1f frames/s · %d ms each · %s · face in %d of %d checks, hand in %d frames".format(
                statFrames * 1000f / (now - statSince), statWorkMs / frames,
                if (present) "you're here" else "nobody in view", statFaces, statFaceChecks, statHands)
            Log.d(TAG, "$status (image ${statConvertMs / frames} ms, hands ${statHandMs / frames} ms)")
            statFrames = 0; statWorkMs = 0; statConvertMs = 0; statHandMs = 0; statSince = now
            statFaces = 0; statFaceChecks = 0; statHands = 0
        }
    }

    private fun onPresence(seen: Boolean, now: Long) {
        if (seen) {
            lastSeen = now
            if (!present) {
                present = true
                listener.onGesture(Gestures.BACK, false)
            }
        } else if (present && now - lastSeen >= awayMs) {
            present = false
            listener.onGesture(Gestures.AWAY, false)
        }
    }

    /**
     * YUV_420_888 to ARGB, turned [rotation]° clockwise and mirrored left to right, reusing one
     * bitmap.
     */
    private fun toBitmap(image: Image, rotation: Int): Bitmap {
        val w = image.width
        val h = image.height
        val sideways = rotation == 90 || rotation == 270
        val outW = if (sideways) h else w
        val outH = if (sideways) w else h
        val bmp = bitmap?.takeIf { it.width == outW && it.height == outH }
            ?: Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also { bitmap = it; pixels = IntArray(w * h) }
        val (yPlane, uPlane, vPlane) = image.planes
        luma = copy(yPlane.buffer, luma)
        chromaU = copy(uPlane.buffer, chromaU)
        chromaV = copy(vPlane.buffer, chromaV)
        val yRow = yPlane.rowStride
        val uvRow = uPlane.rowStride
        val uvPixel = uPlane.pixelStride
        for (j in 0 until h) {
            val yBase = j * yRow
            val uvBase = (j shr 1) * uvRow
            for (i in 0 until w) {
                val y = luma[yBase + i].toInt() and 0xFF
                val uv = uvBase + (i shr 1) * uvPixel
                val u = (chromaU[uv].toInt() and 0xFF) - 128
                val v = (chromaV[uv].toInt() and 0xFF) - 128
                val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (y - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
                val b = (y + 1.772f * u).toInt().coerceIn(0, 255)
                // Where (i, j) lands once turned upright, then mirrored.
                val x: Int
                val y2: Int
                when (rotation) {
                    90 -> { x = h - 1 - j; y2 = i }
                    180 -> { x = w - 1 - i; y2 = h - 1 - j }
                    270 -> { x = j; y2 = w - 1 - i }
                    else -> { x = i; y2 = j }
                }
                pixels[y2 * outW + (outW - 1 - x)] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        return bmp
    }

    private fun copy(buffer: java.nio.ByteBuffer, into: ByteArray): ByteArray {
        val out = if (into.size == buffer.remaining()) into else ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        status = message
        listener.onError(message)
    }

    private companion object {
        val handler by lazy { Handler(HandlerThread("gestures").apply { start() }.looper) }

        const val TAG = "Gestures"
        /** At most ~15 frames a second (the camera's own rate): swipes need several frames. */
        const val FRAME_MS = 66L
        const val FACE_MS = 1_000L
        const val MIN_SCORE = 0.6f
    }
}
