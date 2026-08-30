package com.testlab.camerasec

import android.app.Application
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.testlab.camerasec.camera.CameraController
import com.testlab.camerasec.camera.CameraRunState
import com.testlab.camerasec.camera.LensFacing
import com.testlab.camerasec.cloudinary.CloudinaryUploader
import com.testlab.camerasec.firebase.FirebaseRelay
import com.testlab.camerasec.firebase.RemoteCommandType
import com.testlab.camerasec.log.LogCategory
import com.testlab.camerasec.log.SecurityTestLog
import com.testlab.camerasec.network.LocalStreamServer
import com.testlab.camerasec.util.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** A one-off notice shown on-device whenever the dashboard (LAN or Firebase) triggers a capture. */
data class RemoteEventNotice(
    val id: Long,
    val message: String
)

/**
 * Central state holder for the lab. Owns the CameraController, the
 * LocalStreamServer (same-Wi-Fi dashboard), and the FirebaseRelay
 * (internet/mobile-data dashboard via a pairing code), and exposes plain
 * state for the Compose screens.
 *
 * Nothing in this class starts the camera, the local server, or a Firebase
 * pairing on its own — every start* function here is only ever called from
 * a button press in the UI layer.
 *
 * Remote capture (browser-triggered photo/video/live/switch), whether it
 * arrives via the local LAN server or via Firebase, is OFF by default and
 * can only be turned on from this device via setRemoteCaptureEnabled(true).
 * When it's off, incoming requests are refused rather than silently
 * ignored, and every refusal and every successful remote action is logged
 * and surfaced as a visible on-device notice.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val cameraController = CameraController(application)
    private val firebaseRelay = FirebaseRelay()

    private val _localIp = MutableStateFlow<String?>(null)
    val localIp = _localIp.asStateFlow()

    private var streamServer: LocalStreamServer? = null
    private val _serverRunning = MutableStateFlow(false)
    val serverRunning = _serverRunning.asStateFlow()
    private val _connectedClients = MutableStateFlow(0)
    val connectedClients = _connectedClients.asStateFlow()

    private val _remoteCaptureEnabled = MutableStateFlow(false)
    val remoteCaptureEnabled = _remoteCaptureEnabled.asStateFlow()

    private val _lastRemoteEvent = MutableStateFlow<RemoteEventNotice?>(null)
    val lastRemoteEvent = _lastRemoteEvent.asStateFlow()
    private var remoteEventCounter = 0L

    // --- Firebase pairing (internet / mobile-data dashboard) ---
    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode = _pairingCode.asStateFlow()

    private val _pairingActive = MutableStateFlow(false)
    val pairingActive = _pairingActive.asStateFlow()

    private val _liveActive = MutableStateFlow(false)
    val liveActive = _liveActive.asStateFlow()

    // Kept only to let an incoming SWITCH_CAMERA command rebind the preview
    // that's already on-screen. Cleared whenever the preview is stopped.
    private var lifecycleOwnerRef: WeakReference<LifecycleOwner>? = null
    private var previewViewRef: WeakReference<PreviewView>? = null

    fun setRemoteCaptureEnabled(enabled: Boolean) {
        _remoteCaptureEnabled.value = enabled
        SecurityTestLog.log(
            LogCategory.STREAMING,
            if (enabled) "Allow Remote Capture turned ON by device owner"
            else "Allow Remote Capture turned OFF by device owner"
        )
        val code = _pairingCode.value
        if (code != null) {
            viewModelScope.launch { firebaseRelay.setRemoteCaptureEnabled(code, enabled) }
        }
    }

    fun refreshLocalIp() {
        _localIp.value = NetworkUtils.getLocalIpAddress()
    }

    fun startPreview(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        lifecycleOwnerRef = WeakReference(lifecycleOwner)
        previewViewRef = WeakReference(previewView)
        cameraController.startCamera(lifecycleOwner, previewView, cameraController.lensFacing.value)
    }

    fun stopPreview(reason: String = "Stop requested by user") {
        cameraController.stopCamera(reason)
    }

    fun switchCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraController.switchCamera(lifecycleOwner, previewView)
        publishPairingCameraState()
    }

    /** Starts the local-network-only streaming server (same-Wi-Fi dashboard). Requires the camera to already be running. */
    fun startStreaming(assetProvider: (String) -> ByteArray?) {
        refreshLocalIp()
        val ip = _localIp.value
        if (ip == null) {
            SecurityTestLog.log(LogCategory.STREAMING, "Cannot start streaming — no local network IP available")
            return
        }

        val server = LocalStreamServer(
            bindAddress = ip,
            assetProvider = assetProvider,
            onFrameRequested = { active ->
                cameraController.onFrameJpeg = if (active) { jpeg ->
                    viewModelScope.launch { streamServer?.pushFrame(jpeg) }
                } else null
            },
            isRemoteCaptureAllowed = { _remoteCaptureEnabled.value },
            onRemotePhotoRequested = { source, onResult ->
                postRemoteNotice("Photo capture requested from LAN dashboard")
                cameraController.capturePhoto(source, onResult)
            },
            onRemoteVideoRequested = { source, onResult ->
                postRemoteNotice("Video clip requested from LAN dashboard")
                cameraController.captureVideoClip(
                    source = source,
                    durationMillis = 5000L,
                    outputDir = getApplication<Application>().cacheDir,
                    onResult = onResult
                )
            }
        )
        streamServer = server
        val started = server.start()
        _serverRunning.value = started

        viewModelScope.launch {
            server.connectedClients.collect { _connectedClients.value = it }
        }
    }

    fun stopStreaming() {
        streamServer?.stop()
        streamServer = null
        _serverRunning.value = false
        _connectedClients.value = 0
        cameraController.onFrameJpeg = null
    }

    // --- Firebase pairing lifecycle ---

    /**
     * Creates a new pairing code and starts listening for commands from
     * whichever dashboard (e.g. hosted on Netlify) the user enters this
     * code into. This works over any internet connection, Wi-Fi or mobile
     * data, since Firebase itself is the relay — not this device's LAN IP.
     */
    fun startFirebasePairing() {
        if (_pairingActive.value) return
        val code = firebaseRelay.generatePairingCode()
        viewModelScope.launch {
            firebaseRelay.createPairing(code)
            _pairingCode.value = code
            _pairingActive.value = true
            firebaseRelay.listenForCommands(code) { command -> handleRemoteCommand(command.type) }
            SecurityTestLog.log(LogCategory.CONNECTION, "Firebase pairing active — code $code")
        }
    }

    fun stopFirebasePairing() {
        val code = _pairingCode.value ?: return
        viewModelScope.launch {
            firebaseRelay.endPairing(code)
            _pairingCode.value = null
            _pairingActive.value = false
            _liveActive.value = false
        }
    }

    private fun handleRemoteCommand(type: RemoteCommandType) {
        val code = _pairingCode.value ?: return
        viewModelScope.launch {
            val allowed = firebaseRelay.isRemoteCaptureAllowed(code)
            if (!allowed) {
                SecurityTestLog.log(
                    LogCategory.CONNECTION,
                    "Remote command $type REFUSED — device owner has not enabled Allow Remote Capture."
                )
                return@launch
            }

            when (type) {
                RemoteCommandType.SWITCH_CAMERA -> {
                    val owner = lifecycleOwnerRef?.get()
                    val pv = previewViewRef?.get()
                    if (owner != null && pv != null) {
                        postRemoteNotice("Camera switch requested from dashboard")
                        switchCamera(owner, pv)
                    }
                }
                RemoteCommandType.CAPTURE_PHOTO -> {
                    postRemoteNotice("Photo capture requested from dashboard")
                    cameraController.capturePhoto("firebase dashboard") { bytes ->
                        if (bytes != null) {
                            viewModelScope.launch {
                                val url = CloudinaryUploader.upload(code, bytes, "photo", "jpg", "image")
                                if (url != null) firebaseRelay.publishResult(code, "photo", url)
                            }
                        }
                    }
                }
                RemoteCommandType.CAPTURE_VIDEO -> {
                    postRemoteNotice("Video clip requested from dashboard")
                    cameraController.captureVideoClip(
                        source = "firebase dashboard",
                        durationMillis = 5000L,
                        outputDir = getApplication<Application>().cacheDir
                    ) { bytes ->
                        if (bytes != null) {
                            viewModelScope.launch {
                                val url = CloudinaryUploader.upload(code, bytes, "video", "mp4", "video")
                                if (url != null) firebaseRelay.publishResult(code, "video", url)
                            }
                        }
                    }
                }
                RemoteCommandType.START_LIVE -> startLiveUpload(code)
                RemoteCommandType.STOP_LIVE -> stopLiveUpload()
            }
        }
    }

    /**
     * "Live" over Firebase/Cloudinary means: push a fresh JPEG frame to
     * Cloudinary roughly every 1.5s and update the pairing doc's
     * liveFrameUrl each time. This is NOT continuous low-latency video —
     * it's a periodic snapshot feed, which is what makes true "watch from
     * anywhere on any network" possible without a WebRTC/TURN setup.
     */
    private fun startLiveUpload(code: String) {
        if (_liveActive.value) return
        _liveActive.value = true
        cameraController.liveFrameCaptureActive = true
        postRemoteNotice("Live view started from dashboard")
        viewModelScope.launch { firebaseRelay.updatePairingState(code, mapOf("liveActive" to true)) }

        viewModelScope.launch {
            while (_liveActive.value) {
                val frame = cameraController.captureLatestFrameForLive()
                if (frame != null) {
                    val url = CloudinaryUploader.upload(code, frame, "live", "jpg", "image")
                    if (url != null) firebaseRelay.publishLiveFrame(code, url)
                }
                kotlinx.coroutines.delay(1500L)
            }
        }
    }

    private fun stopLiveUpload() {
        _liveActive.value = false
        cameraController.liveFrameCaptureActive = false
        postRemoteNotice("Live view stopped")
        val code = _pairingCode.value
        if (code != null) {
            viewModelScope.launch { firebaseRelay.updatePairingState(code, mapOf("liveActive" to false)) }
        }
    }

    private fun publishPairingCameraState() {
        val code = _pairingCode.value ?: return
        viewModelScope.launch {
            firebaseRelay.updatePairingState(
                code,
                mapOf(
                    "lensFacing" to cameraController.lensFacing.value.name,
                    "cameraRunning" to (cameraController.runState.value == CameraRunState.RUNNING)
                )
            )
        }
    }

    private fun postRemoteNotice(message: String) {
        remoteEventCounter += 1
        _lastRemoteEvent.value = RemoteEventNotice(remoteEventCounter, message)
    }

    fun lensFacingLabel(): String =
        if (cameraController.lensFacing.value == LensFacing.FRONT) "Front" else "Back"

    override fun onCleared() {
        super.onCleared()
        stopStreaming()
        stopFirebasePairing()
        cameraController.shutdown()
    }
}
