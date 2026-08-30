package dev.grokdesktop.quest

import android.webkit.JavascriptInterface
import org.json.JSONObject

class GrokJsBridge(
    private val complete: (id: String, err: String?, value: Any?) -> Unit,
) {
    @JavascriptInterface
    fun pickFolder(id: String, @Suppress("UNUSED_PARAMETER") argsJson: String) {
        complete(id, "not implemented", null)
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
