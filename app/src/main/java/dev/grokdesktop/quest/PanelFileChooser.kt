package dev.grokdesktop.quest

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.result.ActivityResult

/**
 * WebView `<input type="file">` (image attach, optional voice file).
 * Default WebChromeClient does not open a picker.
 */
class PanelFileChooser(
    private val launch: (Intent) -> Unit,
) {
    companion object {
        const val MAX_URIS = 8

        fun parseResult(result: ActivityResult): Array<Uri>? {
            if (result.resultCode != Activity.RESULT_OK) return null
            val data = result.data ?: return null
            val uris = ArrayList<Uri>(MAX_URIS)
            val clip = data.clipData
            if (clip != null) {
                val n = minOf(clip.itemCount, MAX_URIS)
                for (i in 0 until n) {
                    clip.getItemAt(i)?.uri?.let { uris.add(it) }
                }
            } else {
                data.data?.let { uris.add(it) }
            }
            if (uris.isEmpty()) return null
            return uris.toTypedArray()
        }
    }

    private var pending: ValueCallback<Array<Uri>>? = null

    fun onShowFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: WebChromeClient.FileChooserParams?,
    ): Boolean {
        cancelPending()
        if (filePathCallback == null) return false
        pending = filePathCallback
        val intent = buildIntent(fileChooserParams)
        return try {
            launch(intent)
            true
        } catch (_: Exception) {
            cancelPending()
            false
        }
    }

    fun onActivityResult(result: ActivityResult) {
        val uris = parseResult(result)
        val cb = pending
        pending = null
        cb?.onReceiveValue(uris)
    }

    fun cancelPending() {
        val cb = pending
        pending = null
        cb?.onReceiveValue(null)
    }

    private fun buildIntent(params: WebChromeClient.FileChooserParams?): Intent {
        val created = try {
            params?.createIntent()
        } catch (_: Exception) {
            null
        }
        val intent = created ?: defaultGetContent()
        if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    private fun defaultGetContent(): Intent {
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/*"),
            )
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }
}
