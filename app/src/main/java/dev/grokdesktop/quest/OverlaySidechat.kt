package dev.grokdesktop.quest

import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.UUID

/** In-panel WebView for `/btw` (Electron side chat window). */
class OverlaySidechat(activity: AppCompatActivity) {
    companion object {
        private const val INIT_TTL_MS = 60_000L
        private val NONCE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

        fun isSafeNonce(id: String): Boolean {
            if (id.isEmpty() || id.length > 128) return false
            if (id.contains("..") || id.contains("/") || id.contains("\\")) return false
            return NONCE.matches(id)
        }
    }

    private val root: View = activity.findViewById(R.id.sidechatOverlay)
    val webView: WebView = activity.findViewById(R.id.sidechatWebview)
    private val close: Button = activity.findViewById(R.id.btnSidechatClose)
    private val title: TextView = activity.findViewById(R.id.sidechatTitle)
    private val handler = Handler(Looper.getMainLooper())
    private val inits = HashMap<String, JSONObject>()
    private var wired = false
    var onDismiss: (() -> Unit)? = null

    init {
        title.setText(R.string.sidechat_title)
        close.setOnClickListener { dismiss() }
        root.visibility = View.GONE
    }

    fun isOpen(): Boolean = root.visibility == View.VISIBLE

    fun putInit(payload: JSONObject): String {
        val nonce = UUID.randomUUID().toString()
        inits[nonce] = payload
        handler.postDelayed({ inits.remove(nonce) }, INIT_TTL_MS)
        return nonce
    }

    fun takeInit(nonce: String): JSONObject? {
        if (!isSafeNonce(nonce)) return null
        return inits.remove(nonce)
    }

    fun showUrl(url: String, wire: (WebView) -> Unit) {
        if (!wired) {
            wire(webView)
            wired = true
        }
        root.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    fun dismiss() {
        if (!isOpen()) return
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
        } catch (_: Exception) {
        }
        root.visibility = View.GONE
        onDismiss?.invoke()
    }

    fun destroy() {
        inits.clear()
        handler.removeCallbacksAndMessages(null)
        dismiss()
        try {
            webView.destroy()
        } catch (_: Exception) {
        }
    }
}
