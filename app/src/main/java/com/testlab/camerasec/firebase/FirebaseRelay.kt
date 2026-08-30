package com.testlab.camerasec.firebase

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.testlab.camerasec.log.LogCategory
import com.testlab.camerasec.log.SecurityTestLog
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

/** Remote commands the dashboard can send. Deliberately a closed, small set — no free-form commands. */
enum class RemoteCommandType {
    SWITCH_CAMERA,
    CAPTURE_PHOTO,
    CAPTURE_VIDEO,
    START_LIVE,
    STOP_LIVE
}

data class RemoteCommand(
    val id: String,
    val type: RemoteCommandType
)

/**
 * Bridges the device to Firestore so a dashboard hosted anywhere (e.g. on
 * Netlify) can pair with this specific device over the internet — not just
 * the local Wi-Fi network — using a short-lived pairing code.
 *
 * Actual photo/video/live-frame bytes are uploaded to Cloudinary (see
 * CloudinaryUploader), not Firebase Storage — this keeps everything on
 * Firebase's free Spark plan. FirebaseRelay only ever stores the resulting
 * Cloudinary URL as a string field on the pairing document, exactly the way
 * it previously stored a Firebase Storage download URL, so the dashboard
 * side needed no changes.
 *
 * Safety properties (please keep these if you modify this file):
 *  - A pairing document only exists after the user grants camera permission
 *    on THIS device and the app explicitly creates it. Nothing is created
 *    automatically or in the background.
 *  - remoteCaptureEnabled lives in the pairing document and is set ONLY by
 *    this device (see MainViewModel.setRemoteCaptureEnabled). The device
 *    itself decides whether to actually act on incoming commands based on
 *    this flag — Firestore rules additionally prevent the dashboard from
 *    writing that field at all (see firestore.rules).
 *  - Every command consumed and every result published is written to
 *    SecurityTestLog, and results in a visible on-device notice — see
 *    MainViewModel.postRemoteNotice.
 */
class FirebaseRelay {

    private val db = FirebaseFirestore.getInstance()

    private var commandListener: ListenerRegistration? = null
    private var pairingListener: ListenerRegistration? = null

    /** Generates a short, human-typeable pairing code, e.g. "7F3K-9QZP". */
    fun generatePairingCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I ambiguity
        fun block() = (1..4).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        return "${block()}-${block()}"
    }

    /** Creates the pairing document for this device. Called once, right after camera permission is granted. */
    suspend fun createPairing(code: String) {
        val data = hashMapOf(
            "createdAt" to com.google.firebase.Timestamp.now(),
            "remoteCaptureEnabled" to false,
            "lensFacing" to "BACK",
            "cameraRunning" to false,
            "liveActive" to false,
            "liveFrameUrl" to null,
            "lastResultType" to null,
            "lastResultUrl" to null
        )
        db.collection("pairings").document(code).set(data).await()
        SecurityTestLog.log(LogCategory.CONNECTION, "Firebase pairing created: $code")
    }

    /** Updates simple state fields on the pairing document (lens facing, running state, etc). */
    suspend fun updatePairingState(code: String, fields: Map<String, Any?>) {
        runCatching {
            db.collection("pairings").document(code).update(fields).await()
        }
    }

    suspend fun setRemoteCaptureEnabled(code: String, enabled: Boolean) {
        updatePairingState(code, mapOf("remoteCaptureEnabled" to enabled))
    }

    /** True only if the pairing doc's remoteCaptureEnabled flag is currently true. Re-read live, never cached long-term. */
    suspend fun isRemoteCaptureAllowed(code: String): Boolean {
        return runCatching {
            val doc = db.collection("pairings").document(code).get().await()
            doc.getBoolean("remoteCaptureEnabled") ?: false
        }.getOrDefault(false)
    }

    /**
     * Starts listening for new commands written by the dashboard. Each command
     * is marked consumed=true immediately after being read so it never fires
     * twice, and the whole listener is scoped to this pairing code's
     * subcollection only.
     */
    fun listenForCommands(code: String, onCommand: (RemoteCommand) -> Unit) {
        stopListeningForCommands()
        commandListener = db.collection("pairings").document(code)
            .collection("commands")
            .whereEqualTo("consumed", false)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                for (change in snapshot.documentChanges) {
                    if (change.type.name != "ADDED") continue
                    val doc = change.document
                    val typeStr = doc.getString("type") ?: continue
                    val type = runCatching { RemoteCommandType.valueOf(typeStr) }.getOrNull() ?: continue
                    // Mark consumed right away so a slow handler can't double-fire on reconnect.
                    doc.reference.update("consumed", true)
                    onCommand(RemoteCommand(doc.id, type))
                }
            }
    }

    fun stopListeningForCommands() {
        commandListener?.remove()
        commandListener = null
    }

    /** Live-updates a local callback whenever the pairing document itself changes (for dashboard-side use, or device-side reflection). */
    fun listenForPairingState(code: String, onChange: (DocumentSnapshot) -> Unit) {
        pairingListener?.remove()
        pairingListener = db.collection("pairings").document(code)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                onChange(snapshot)
            }
    }

    fun stopListeningForPairingState() {
        pairingListener?.remove()
        pairingListener = null
    }

    /** Records the result of a photo/video capture on the pairing document so the dashboard's listener picks it up. */
    suspend fun publishResult(code: String, kind: String, url: String) {
        updatePairingState(
            code,
            mapOf(
                "lastResultType" to kind,
                "lastResultUrl" to url,
                "lastResultAt" to com.google.firebase.Timestamp.now()
            )
        )
    }

    /** Records a new live-view frame URL on the pairing document. */
    suspend fun publishLiveFrame(code: String, url: String) {
        updatePairingState(
            code,
            mapOf(
                "liveFrameUrl" to url,
                "liveFrameUpdatedAt" to com.google.firebase.Timestamp.now()
            )
        )
    }

    /** Deletes the pairing document and stops all listeners — used when the user ends the session on-device. */
    suspend fun endPairing(code: String) {
        stopListeningForCommands()
        stopListeningForPairingState()
        runCatching { db.collection("pairings").document(code).delete().await() }
        SecurityTestLog.log(LogCategory.CONNECTION, "Firebase pairing ended: $code")
    }
}
