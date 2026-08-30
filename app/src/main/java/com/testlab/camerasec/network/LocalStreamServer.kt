package com.testlab.camerasec.network

import com.testlab.camerasec.log.LogCategory
import com.testlab.camerasec.log.SecurityTestLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

private const val BOUNDARY = "camseclabframe"
const val LOCAL_STREAM_PORT = 8089

/**
 * A minimal local-only MJPEG-over-HTTP server for this device's own testing
 * dashboard.
 *
 * Safety properties (please keep these if you modify this file):
 *  - Binds explicitly to the device's current LAN IPv4 address, never to
 *    0.0.0.0-with-router-forwarding or a public relay. If no LAN IP is
 *    available, it refuses to start.
 *  - Only starts when the user presses "Start Streaming" in the UI, and
 *    fully stops (socket closed, thread joined) when the user presses
 *    "Stop Streaming" or the app leaves a foreground-capable state.
 *  - Serves the static dashboard (index.html/js/css, read from app assets),
 *    a live MJPEG frame feed, and — ONLY when isRemoteCaptureAllowed()
 *    returns true — two capture endpoints used purely to test that a
 *    browser-triggered action really does cause a real network round trip
 *    to and from the device. No file system browsing, no arbitrary command
 *    endpoints, no remote control of anything off-device.
 *  - Every connect/disconnect AND every capture request is written to
 *    SecurityTestLog so the person running the test can see exactly what
 *    happened and when.
 *  - isRemoteCaptureAllowed is a live callback (not a snapshot taken at
 *    server-start time), so the on-device "Allow Remote Capture" toggle
 *    takes effect immediately for the next request, in both directions.
 */
class LocalStreamServer(
    private val bindAddress: String,
    private val assetProvider: (String) -> ByteArray?,
    private val onFrameRequested: (Boolean) -> Unit,
    private val isRemoteCaptureAllowed: () -> Boolean,
    private val onRemotePhotoRequested: (source: String, onResult: (ByteArray?) -> Unit) -> Unit,
    private val onRemoteVideoRequested: (source: String, onResult: (ByteArray?) -> Unit) -> Unit
) {
    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _connectedClients = MutableStateFlow(0)
    val connectedClients = _connectedClients.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val latestFrameLock = Mutex()
    private var latestFrame: ByteArray? = null

    private val streamSockets = CopyOnWriteArraySet<Socket>()
    private val clientCounter = AtomicInteger(0)

    fun start(): Boolean {
        if (_isRunning.value) return true

        return try {
            val addr = InetAddress.getByName(bindAddress)
            val socket = ServerSocket(LOCAL_STREAM_PORT, 50, addr)
            serverSocket = socket
            _isRunning.value = true
            SecurityTestLog.log(
                LogCategory.STREAMING,
                "STREAMING ACTIVE — local server bound to $bindAddress:$LOCAL_STREAM_PORT"
            )
            onFrameRequested(true)

            acceptJob = scope.launch {
                while (_isRunning.value) {
                    try {
                        val client = socket.accept()
                        scope.launch { handleClient(client) }
                    } catch (e: IOException) {
                        if (_isRunning.value) {
                            SecurityTestLog.log(LogCategory.CONNECTION, "Accept loop error: ${e.message}")
                        }
                        break
                    }
                }
            }
            true
        } catch (e: Exception) {
            SecurityTestLog.log(LogCategory.STREAMING, "Failed to start local server: ${e.message}")
            _isRunning.value = false
            false
        }
    }

    fun stop() {
        if (!_isRunning.value) return
        _isRunning.value = false
        onFrameRequested(false)

        streamSockets.forEach { runCatching { it.close() } }
        streamSockets.clear()
        _connectedClients.value = 0

        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()

        SecurityTestLog.log(LogCategory.STREAMING, "Streaming stopped — local server closed")
    }

    /** Called by CameraController's frame callback whenever a new JPEG frame is ready. */
    suspend fun pushFrame(jpeg: ByteArray) {
        latestFrameLock.withLock { latestFrame = jpeg }
    }

    private suspend fun handleClient(socket: Socket) {
        val clientId = clientCounter.incrementAndGet()
        val remote = socket.inetAddress?.hostAddress ?: "unknown"
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            // Drain remaining request headers.
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
            }

            val requestParts = requestLine.split(" ")
            val method = requestParts.getOrNull(0) ?: "GET"
            val path = requestParts.getOrNull(1) ?: "/"
            SecurityTestLog.log(LogCategory.CONNECTION, "Dashboard client #$clientId connected from $remote → $method $path")

            val output = socket.getOutputStream()
            when {
                path == "/stream" -> {
                    streamSockets.add(socket)
                    _connectedClients.value = streamSockets.size
                    serveMjpegStream(socket, output)
                }
                path == "/" || path == "/index.html" -> serveAsset(output, "dashboard/index.html", "text/html")
                path == "/dashboard.css" -> serveAsset(output, "dashboard/dashboard.css", "text/css")
                path == "/dashboard.js" -> serveAsset(output, "dashboard/dashboard.js", "application/javascript")
                path == "/status" -> serveStatusJson(output)
                path == "/capture/photo" -> handleCaptureRequest(output, remote, isVideo = false)
                path == "/capture/video" -> handleCaptureRequest(output, remote, isVideo = true)
                else -> serveNotFound(output)
            }
        } catch (e: IOException) {
            // Normal on disconnect; nothing to do.
        } finally {
            streamSockets.remove(socket)
            _connectedClients.value = streamSockets.size
            runCatching { socket.close() }
            SecurityTestLog.log(LogCategory.CONNECTION, "Dashboard client #$clientId disconnected ($remote)")
        }
    }

    /**
     * Handles a remote-triggered photo or video capture request. This is the
     * ONLY place a browser request can cause the camera to actually capture
     * anything, and it is gated on isRemoteCaptureAllowed() — which reflects
     * a toggle the device owner controls on-device, defaulting to off. If
     * that gate is closed, this returns 403 and logs the refusal; it never
     * silently captures anyway.
     */
    private suspend fun handleCaptureRequest(output: OutputStream, remote: String, isVideo: Boolean) {
        if (!isRemoteCaptureAllowed()) {
            SecurityTestLog.log(
                LogCategory.CONNECTION,
                "Remote capture REFUSED from $remote — device owner has not enabled Allow Remote Capture."
            )
            val body = """{"error":"remote_capture_disabled"}"""
            val header = "HTTP/1.1 403 Forbidden\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(body.toByteArray())
            output.flush()
            return
        }

        val source = "remote dashboard ($remote)"
        val startedAt = System.currentTimeMillis()

        val bytes: ByteArray? = suspendCancellableCoroutine { cont ->
            val callback: (ByteArray?) -> Unit = { result ->
                if (cont.isActive) cont.resume(result)
            }
            if (isVideo) onRemoteVideoRequested(source, callback) else onRemotePhotoRequested(source, callback)
        }

        val elapsedMs = System.currentTimeMillis() - startedAt

        if (bytes == null) {
            val body = """{"error":"capture_failed"}"""
            val header = "HTTP/1.1 500 Internal Server Error\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(body.toByteArray())
            output.flush()
            return
        }

        val contentType = if (isVideo) "video/mp4" else "image/jpeg"
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "X-Capture-Duration-Ms: $elapsedMs\r\n" +
            "X-Capture-Bytes: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(bytes)
        output.flush()

        SecurityTestLog.log(
            LogCategory.CONNECTION,
            "Remote ${if (isVideo) "video" else "photo"} capture served to $remote — " +
                "${bytes.size} bytes in ${elapsedMs}ms"
        )
    }

    private fun serveAsset(output: OutputStream, assetPath: String, contentType: String) {
        val bytes = assetProvider(assetPath)
        if (bytes == null) {
            serveNotFound(output)
            return
        }
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun serveStatusJson(output: OutputStream) {
        val body = """{"streaming":${_isRunning.value},"clients":${_connectedClients.value},"remoteCaptureAllowed":${isRemoteCaptureAllowed()}}"""
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(body.toByteArray())
        output.flush()
    }

    private fun serveNotFound(output: OutputStream) {
        val body = "Not found"
        val header = "HTTP/1.1 404 Not Found\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(body.toByteArray())
        output.flush()
    }

    private suspend fun serveMjpegStream(socket: Socket, output: OutputStream) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
            "Cache-Control: no-store, no-cache, must-revalidate, private\r\n" +
            "Pragma: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.flush()

        while (_isRunning.value && !socket.isClosed) {
            val frame = latestFrameLock.withLock { latestFrame }
            if (frame != null) {
                try {
                    val part = "--$BOUNDARY\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${frame.size}\r\n\r\n"
                    output.write(part.toByteArray())
                    output.write(frame)
                    output.write("\r\n".toByteArray())
                    output.flush()
                } catch (e: IOException) {
                    break // client disconnected
                }
            }
            kotlinx.coroutines.delay(120) // ~8 fps cap, deliberately modest for a LAN test tool
        }
    }
}
