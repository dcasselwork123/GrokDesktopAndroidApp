package dev.grokdesktop.quest

import android.webkit.JavascriptInterface
import org.json.JSONObject

class GrokJsBridge(
    private val complete: (id: String, err: String?, value: Any?) -> Unit,
    private val openExternalUrl: (url: String) -> Boolean,
    private val copyTextToClipboard: (text: String) -> Boolean,
) {
    @JavascriptInterface
    fun pickFolder(id: String, @Suppress("UNUSED_PARAMETER") argsJson: String) {
        complete(id, "not implemented", null)
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
    fun setTheme(id: String, argsJson: String) {
        val pref = try {
            JSONObject(argsJson).optString("pref", "")
        } catch (_: Exception) {
            ""
        }
        complete(id, null, pref)
    }

    @JavascriptInterface
    fun openSidechat(id: String, @Suppress("UNUSED_PARAMETER") argsJson: String) {
        complete(id, "not implemented", null)
    }

    @JavascriptInterface
    fun getSidechatInit(id: String, @Suppress("UNUSED_PARAMETER") argsJson: String) {
        complete(id, "not implemented", null)
    }

    @JavascriptInterface
    fun startPtt(id: String, @Suppress("UNUSED_PARAMETER") argsJson: String) {
        complete(id, "not implemented", null)
    }

    @JavascriptInterface
    fun stopPtt(id: String, @Suppress("UNUSED_PARAMETER") argsJson: String) {
        complete(id, "not implemented", null)
    }
}
