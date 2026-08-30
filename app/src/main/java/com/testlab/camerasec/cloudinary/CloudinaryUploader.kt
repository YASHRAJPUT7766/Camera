package com.testlab.camerasec.cloudinary

import com.testlab.camerasec.log.LogCategory
import com.testlab.camerasec.log.SecurityTestLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Uploads captured photos/videos directly to Cloudinary using an UNSIGNED
 * upload preset, so the device never needs to hold a Cloudinary API secret.
 *
 * This replaces Firebase Storage (which requires the paid Blaze plan) —
 * Firestore is still used for everything else (pairing state, commands,
 * and now also for storing the Cloudinary URL that comes back).
 *
 * Safety properties (please keep these if you modify this file):
 *  - Every upload goes into a folder scoped to the pairing code
 *    (pairings/{code}/...), same isolation model as before.
 *  - Only ever uploads bytes the device itself just captured — this class
 *    has no path that reads or uploads anything else from the device.
 *  - The upload preset must stay set to "Unsigned" with signing disabled;
 *    if you ever switch it to "Signed" in the Cloudinary console, this
 *    class will start failing (that's intentional — it should never be
 *    handed a secret API key to embed in the app).
 */
object CloudinaryUploader {

    // From the Cloudinary console (Settings -> Upload -> Upload presets).
    private const val CLOUD_NAME = "dljzyticd"
    private const val UPLOAD_PRESET = "YASHRAJPUT"

    private fun endpointFor(resourceType: String) =
        "https://api.cloudinary.com/v1_1/$CLOUD_NAME/$resourceType/upload"

    /**
     * Uploads [bytes] as either "image" or "video" resourceType, tagged into
     * a pairing-scoped folder, and returns the public secure_url Cloudinary
     * gives back — or null if the upload failed for any reason.
     */
    suspend fun upload(
        pairingCode: String,
        bytes: ByteArray,
        kind: String,       // "photo" | "video" | "live"
        extension: String,  // "jpg" | "mp4"
        resourceType: String // "image" | "video"
    ): String? = withContext(Dispatchers.IO) {
        val boundary = "CamSecLab${UUID.randomUUID()}"
        val fileName = "${kind}_${System.currentTimeMillis()}.$extension"
        val folder = "pairings/$pairingCode"

        try {
            val url = URL(endpointFor(resourceType))
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connectTimeout = 15000
                readTimeout = 20000
            }

            connection.outputStream.use { out ->
                fun writeField(name: String, value: String) {
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                    out.write("$value\r\n".toByteArray())
                }

                writeField("upload_preset", UPLOAD_PRESET)
                writeField("folder", folder)

                out.write("--$boundary\r\n".toByteArray())
                out.write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n".toByteArray()
                )
                out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
                out.write(bytes)
                out.write("\r\n".toByteArray())

                out.write("--$boundary--\r\n".toByteArray())
            }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader()
                .use { it.readText() }

            if (responseCode !in 200..299) {
                SecurityTestLog.log(
                    LogCategory.CONNECTION,
                    "Cloudinary upload failed ($kind): HTTP $responseCode — $responseBody"
                )
                return@withContext null
            }

            val json = JSONObject(responseBody)
            val secureUrl = json.optString("secure_url", "")
            if (secureUrl.isEmpty()) {
                SecurityTestLog.log(LogCategory.CONNECTION, "Cloudinary upload succeeded but no secure_url returned")
                return@withContext null
            }

            SecurityTestLog.log(
                LogCategory.CONNECTION,
                "Uploaded $kind to Cloudinary (${bytes.size} bytes)"
            )
            secureUrl
        } catch (e: Exception) {
            SecurityTestLog.log(LogCategory.CONNECTION, "Cloudinary upload error ($kind): ${e.message}")
            null
        }
    }
}
