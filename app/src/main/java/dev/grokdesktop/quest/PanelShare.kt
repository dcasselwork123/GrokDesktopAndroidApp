package dev.grokdesktop.quest

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.URLUtil
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** ACTION_SEND share sheet for /export transcripts and WebView downloads. */
object PanelShare {
    const val AUTHORITY_SUFFIX = ".share"
    const val MAX_SHARE_BYTES = 64L * 1024 * 1024
    const val MAX_BRIDGE_BYTES = 8L * 1024 * 1024
    private const val TAG = "GrokShare"

    fun sanitizeFilename(name: String, fallback: String = "download"): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.')
        val cut = cleaned.take(80)
        return if (cut.isBlank() || cut == "." || cut == "..") fallback else cut
    }

    fun isLoopbackHttp(url: String, boundPort: Int): Boolean {
        if (boundPort <= 0) return false
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return false
        }
        val host = uri.host ?: return false
        if (host != "127.0.0.1" && host != "localhost") return false
        if (uri.scheme != "http") return false
        return uri.port == boundPort
    }

    fun shareFromBridge(activity: AppCompatActivity, payload: JSONObject): Boolean {
        val filename = sanitizeFilename(payload.optString("filename", ""), "chat.md")
        val mimeHint = payload.optString("mimeType", "").ifBlank { "application/octet-stream" }
        val text = payload.optString("text", "")
        if (text.isNotEmpty()) {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_BRIDGE_BYTES) return false
            val mime = mimeHint.ifBlank { "text/markdown" }
            return shareBytes(activity, bytes, filename, mime)
        }
        val dataUrl = payload.optString("dataUrl", "")
        if (dataUrl.startsWith("data:")) {
            val decoded = decodeDataUrl(dataUrl) ?: return false
            if (decoded.first.size > MAX_BRIDGE_BYTES) return false
            val mime = mimeHint.ifBlank { decoded.second }
            return shareBytes(activity, decoded.first, filename, mime)
        }
        return false
    }

    fun shareDataUrl(activity: AppCompatActivity, dataUrl: String, filename: String, mimeHint: String): Boolean {
        val decoded = decodeDataUrl(dataUrl) ?: return false
        if (decoded.first.size > MAX_BRIDGE_BYTES) return false
        val mime = mimeHint.ifBlank { decoded.second }
        return shareBytes(activity, decoded.first, sanitizeFilename(filename), mime)
    }

    data class Downloaded(val bytes: ByteArray, val filename: String, val mimeType: String)

    fun downloadLoopback(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        boundPort: Int,
    ): Downloaded? {
        if (!isLoopbackHttp(url, boundPort)) return null
        val name = sanitizeFilename(
            URLUtil.guessFileName(url, contentDisposition, mimeType),
            "download",
        )
        val mime = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: guessMime(name)
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) return null
            val declared = conn.contentLengthLong
            if (declared > MAX_SHARE_BYTES) return null
            val bytes = conn.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val tmp = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(tmp)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_SHARE_BYTES) return@use null
                    buf.write(tmp, 0, n)
                }
                buf.toByteArray()
            }
            if (bytes == null || bytes.isEmpty()) return null
            Downloaded(bytes, name, mime)
        } catch (t: Exception) {
            Log.w(TAG, "download failed: ${t.javaClass.simpleName}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun shareBytes(
        activity: AppCompatActivity,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
    ): Boolean {
        if (bytes.isEmpty()) return false
        val dir = File(activity.cacheDir, "shares")
        if (!dir.exists() && !dir.mkdirs()) return false
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, sanitizeFilename(filename))
        try {
            file.writeBytes(bytes)
        } catch (t: Exception) {
            Log.w(TAG, "write share file failed: ${t.javaClass.simpleName}")
            return false
        }
        val uri = try {
            FileProvider.getUriForFile(activity, activity.packageName + AUTHORITY_SUFFIX, file)
        } catch (t: Exception) {
            Log.w(TAG, "FileProvider failed: ${t.javaClass.simpleName}")
            return false
        }
        val mime = mimeType.ifBlank { "application/octet-stream" }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newUri(activity.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            activity.startActivity(
                Intent.createChooser(send, activity.getString(R.string.share_chooser)),
            )
            true
        } catch (t: Exception) {
            Log.w(TAG, "share sheet failed: ${t.javaClass.simpleName}")
            false
        }
    }

    fun decodeDataUrl(dataUrl: String): Pair<ByteArray, String>? {
        if (!dataUrl.startsWith("data:")) return null
        val comma = dataUrl.indexOf(',')
        if (comma < 5) return null
        val header = dataUrl.substring(5, comma)
        val mime = header.substringBefore(';').ifBlank { "application/octet-stream" }
        val isB64 = header.contains(";base64", ignoreCase = true)
        val payload = dataUrl.substring(comma + 1)
        if (payload.length > MAX_BRIDGE_BYTES * 2) return null
        val bytes = try {
            if (isB64) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray(StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {
            return null
        }
        if (bytes.isEmpty() || bytes.size > MAX_BRIDGE_BYTES) return null
        return bytes to mime
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "md", "markdown" -> "text/markdown"
            "txt" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
    }
}
