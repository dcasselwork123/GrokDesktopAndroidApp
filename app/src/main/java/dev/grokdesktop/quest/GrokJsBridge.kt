package dev.grokdesktop.quest

import android.webkit.JavascriptInterface
import org.json.JSONObject

class GrokJsBridge(
    private val complete: (id: String, err: String?, value: Any?) -> Unit,
    private val openExternalUrl: (url: String) -> Boolean,
    private val copyTextToClipboard: (text: String) -> Boolean,
    private val openFolderPicker: (id: String) -> Unit,
    private val shareFilePayload: (id: String, argsJson: String) -> Unit,
    private val startPttPayload: (id: String, argsJson: String) -> Unit,
    private val stopPttPayload: (id: String) -> Unit,
    private val openSidechatPayload: (id: String, argsJson: String) -> Unit,
    private val getSidechatInitPayload: (id: String, argsJson: String) -> Unit,
) {
    @JavascriptInterface
    fun pickFolder(id: String, @Suppress("UNUSED_PARAMETER") argsJson: String) {
        openFolderPicker(id)
    }

    @JavascriptInterface
    fun openExternal(id: String, argsJson: String) {
        val url = try {
            JSONObject(argsJson).optString("url", "")
        } catch (_: Exception) {
            ""
        }
        val ok = try {
            openExternalUrl(url)
        } catch (_: Exception) {
            false
        }
        if (ok) complete(id, null, true) else complete(id, "blocked url", false)
    }

    @JavascriptInterface
    fun copyText(id: String, argsJson: String) {
        val text = try {
            JSONObject(argsJson).optString("text", "")
        } catch (_: Exception) {
            ""
        }
        val ok = try {
            copyTextToClipboard(text)
        } catch (_: Exception) {
            false
        }
        if (ok) complete(id, null, true) else complete(id, "copy failed", false)
    }

    @JavascriptInterface
    fun shareFile(id: String, argsJson: String) {
        shareFilePayload(id, argsJson)
    }

    @JavascriptInterface
    fun setTheme(id: String, argsJson: String) {
        val pref = try {
            JSONObject(argsJson).optString("pref", "")
        } catch (_: Exception) {
            ""
        }
        complete(id, null, pref)
    }

    @JavascriptInterface
    fun openSidechat(id: String, argsJson: String) {
        openSidechatPayload(id, argsJson)
    }

    @JavascriptInterface
    fun getSidechatInit(id: String, argsJson: String) {
        getSidechatInitPayload(id, argsJson)
    }

    @JavascriptInterface
    fun startPtt(id: String, argsJson: String) {
        startPttPayload(id, argsJson)
    }

    @JavascriptInterface
    fun stopPtt(id: String, @Suppress("UNUSED_PARAMETER") argsJson: String) {
        stopPttPayload(id)
    }
}
