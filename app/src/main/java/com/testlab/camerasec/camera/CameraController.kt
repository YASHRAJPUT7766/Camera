package com.testlab.camerasec.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.testlab.camerasec.log.LogCategory
import com.testlab.camerasec.log.SecurityTestLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class LensFacing { FRONT, BACK }

enum class CameraRunState { STOPPED, STARTING, RUNNING, ERROR }

/**
 * Thin, explicit wrapper around CameraX. Everything here happens because the
 * user pressed a visible button in this process's foreground UI. There is no
 * path in this class that starts the camera without a bindToLifecycle call
 * tied to the activity's own lifecycle, and no path that keeps the camera
 * running after that lifecycle stops.
 *
 * Frame callback (onFrameJpeg) is only registered while local streaming is
 * explicitly turned on by the user from the UI (see StreamingScreen /
 * LocalStreamServer). It's used only to serve frames to the same device's
 * local dashboard connection — never uploaded anywhere.
 */
class CameraController(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _runState = MutableStateFlow(CameraRunState.STOPPED)
    val runState = _runState.asStateFlow()

    private val _lensFacing = MutableStateFlow(LensFacing.BACK)
    val lensFacing = _lensFacing.asStateFlow()

    /** Set by the LAN streaming layer when (and only when) the user starts LAN streaming. */
    var onFrameJpeg: ((ByteArray) -> Unit)? = null

    /** Set by MainViewModel while a Firebase "live" session is active, so handleFrame knows to keep converting frames even if onFrameJpeg (LAN) isn't set. */
    @Volatile
    var liveFrameCaptureActive: Boolean = false

    // Always kept up to date with the most recent analyzed frame while the
    // camera is running, regardless of whether onFrameJpeg is set. Used by
    // the Firebase "live" uploader to grab a still on its own schedule
    // without needing a second, competing analyzer.
    @Volatile
    private var latestJpegFrame: ByteArray? = null

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        facing: LensFacing = _lensFacing.value
    ) {
        _runState.value = CameraRunState.STARTING
        SecurityTestLog.log(LogCategory.CAMERA, "Requesting CameraX provider (start requested by user)")

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                bind(provider, lifecycleOwner, previewView, facing)
            } catch (e: Exception) {
                _runState.value = CameraRunState.ERROR
                SecurityTestLog.log(LogCategory.CAMERA, "Camera could not start: ${e.message}")
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }

    private fun bind(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        facing: LensFacing
    ) {
        provider.unbindAll()

        val selector = if (facing == LensFacing.FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
            handleFrame(imageProxy)
        }
        imageAnalysis = analysis

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        imageCapture = capture

        val recorder = Recorder.Builder().build()
        val video = VideoCapture.withOutput(recorder)
        videoCapture = video

        try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis, capture, video)
            _lensFacing.value = facing
            _runState.value = CameraRunState.RUNNING
            SecurityTestLog.log(
                LogCategory.CAMERA,
                "CAMERA ACTIVE — bound to lifecycle (${facing.name} lens)"
            )
        } catch (e: SecurityException) {
            // This is exactly what CameraX throws when CAMERA permission is
            // missing or was revoked. Report it plainly rather than retrying
            // or working around it.
            _runState.value = CameraRunState.ERROR
            SecurityTestLog.log(LogCategory.PERMISSION, "Camera permission unavailable.")
            SecurityTestLog.log(LogCategory.CAMERA, "Camera service could not continue.")
        } catch (e: Exception) {
            _runState.value = CameraRunState.ERROR
            SecurityTestLog.log(LogCategory.CAMERA, "Camera bind failed: ${e.message}")
        }
    }

    fun switchCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val newFacing = if (_lensFacing.value == LensFacing.BACK) LensFacing.FRONT else LensFacing.BACK
        val provider = cameraProvider
        if (provider != null && _runState.value == CameraRunState.RUNNING) {
            bind(provider, lifecycleOwner, previewView, newFacing)
            SecurityTestLog.log(LogCategory.CAMERA_SWITCH, "Switched to ${newFacing.name} camera")
        } else {
            _lensFacing.value = newFacing
        }
    }

    fun stopCamera(reason: String = "Stop requested by user") {
        cameraProvider?.unbindAll()
        _runState.value = CameraRunState.STOPPED
        SecurityTestLog.log(LogCategory.CAMERA, "Camera stopped — $reason")
    }

    private fun handleFrame(imageProxy: ImageProxy) {
        val shouldConvert = onFrameJpeg != null || liveFrameCaptureActive
        if (!shouldConvert) {
            imageProxy.close()
            return
        }
        try {
            val jpeg = imageProxyToJpeg(imageProxy)
            if (jpeg != null) {
                latestJpegFrame = jpeg
                onFrameJpeg?.invoke(jpeg)
            }
        } catch (_: Exception) {
            // Drop the frame silently; streaming is best-effort and must never crash preview.
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Returns the most recently analyzed frame as a JPEG, or null if the
     * camera isn't running / no frame has arrived yet. Used by the Firebase
     * "live" uploader to grab a still on its own timer (~every 1.5s) without
     * needing a dedicated capture call each time.
     */
    fun captureLatestFrameForLive(): ByteArray? {
        if (_runState.value != CameraRunState.RUNNING) return null
        return latestJpegFrame
    }

    /** Converts a YUV_420_888 ImageProxy to a JPEG byte array for MJPEG-style streaming. */
    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray? {
        if (imageProxy.format != ImageFormat.YUV_420_888) return null

        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 70, out)
        return out.toByteArray()
    }

    /**
     * Captures a single JPEG photo using CameraX's ImageCapture use case and
     * returns the bytes via callback. This is triggered either by an on-device
     * button, or — if the user has explicitly turned on "Allow Remote Capture"
     * (see MainViewModel.remoteCaptureEnabled) — by a request arriving at the
     * local streaming server's /capture/photo endpoint. Either way it is the
     * SAME code path and is always logged and reported via SecurityTestLog.
     */
    fun capturePhoto(source: String, onResult: (ByteArray?) -> Unit) {
        val capture = imageCapture
        if (capture == null || _runState.value != CameraRunState.RUNNING) {
            SecurityTestLog.log(LogCategory.CAMERA, "Photo capture failed — camera is not running.")
            onResult(null)
            return
        }
        SecurityTestLog.log(LogCategory.CAMERA, "Photo capture requested ($source)")
        capture.takePicture(
            analysisExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val jpeg = try {
                        imageProxyToJpeg(image)
                    } finally {
                        image.close()
                    }
                    SecurityTestLog.log(
                        LogCategory.CAMERA,
                        "Photo captured (${jpeg?.size ?: 0} bytes) — source: $source"
                    )
                    onResult(jpeg)
                }

                override fun onError(exception: ImageCaptureException) {
                    SecurityTestLog.log(LogCategory.CAMERA, "Photo capture error: ${exception.message}")
                    onResult(null)
                }
            }
        )
    }

    /**
     * Records a short video clip to a temp file and returns its bytes once
     * recording finishes. Same dual-trigger model as capturePhoto: an
     * on-device button, or a remote request that only works when the user
     * has explicitly enabled remote capture on-device.
     */
    fun captureVideoClip(
        source: String,
        durationMillis: Long,
        outputDir: File,
        onResult: (ByteArray?) -> Unit
    ) {
        val video = videoCapture
        if (video == null || _runState.value != CameraRunState.RUNNING) {
            SecurityTestLog.log(LogCategory.CAMERA, "Video capture failed — camera is not running.")
            onResult(null)
            return
        }
        if (activeRecording != null) {
            SecurityTestLog.log(LogCategory.CAMERA, "Video capture rejected — a recording is already in progress.")
            onResult(null)
            return
        }

        val outputFile = File(outputDir, "clip_${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        SecurityTestLog.log(LogCategory.CAMERA, "Video capture requested ($source, ${durationMillis}ms)")

        activeRecording = video.output
            .prepareRecording(context, outputOptions)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        if (event.hasError()) {
                            SecurityTestLog.log(LogCategory.CAMERA, "Video capture error: ${event.error}")
                            onResult(null)
                            outputFile.delete()
                        } else {
                            val bytes = try {
                                outputFile.readBytes()
                            } catch (e: Exception) {
                                null
                            }
                            SecurityTestLog.log(
                                LogCategory.CAMERA,
                                "Video captured (${bytes?.size ?: 0} bytes) — source: $source"
                            )
                            onResult(bytes)
                            outputFile.delete()
                        }
                    }
                    else -> { /* Start/Pause/Resume/Status events — no action needed */ }
                }
            }

        // Stop automatically after the requested duration — this is a short
        // test clip, not open-ended recording.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            activeRecording?.stop()
        }, durationMillis)
    }

    fun shutdown() {
        activeRecording?.stop()
        activeRecording = null
        stopCamera("Controller shutdown")
        analysisExecutor.shutdown()
    }
}
