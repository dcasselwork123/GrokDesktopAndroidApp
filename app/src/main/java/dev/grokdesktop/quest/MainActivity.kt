package dev.grokdesktop.quest

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.graphics.Bitmap
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PLACEHOLDER_URL = "file:///android_asset/placeholder.html"
        private const val TAG_WEB = "GrokWeb"
        private val ERROR_STATES = setOf(
            "error",
            "exited",
            "handshake-timeout",
            "stopped",
            "destroyed",
        )
    }

    private lateinit var webView: WebView
    private lateinit var btnRetry: Button
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var boundPort: Int = -1
    private var loadedLoopback = false
    private var placeholderReady = false
    private var pendingPlaceholder: JSONObject? = null
    private var micPrimed = false
    private var micRequestInFlight = false
    private var pendingPttStart: Pair<String, String>? = null
    private val ptt = PttAudioRecord()
    @Volatile private var uiDead = false

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startRuntime()
        } else {
            showPlaceholder(
                getString(R.string.runtime_denied),
                getString(R.string.perm_notifications_required),
            )
            btnRetry.visibility = View.VISIBLE
        }
        primeMic()
    }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micRequestInFlight = false
        val pending = pendingPttStart
        pendingPttStart = null
        if (pending != null) {
            if (granted) beginPtt(pending.first, pending.second)
            else completeBridge(pending.first, "microphone permission denied", false)
        }
    }

    private lateinit var folderPicker: FolderPicker
    private lateinit var overlaySidechat: OverlaySidechat
    private lateinit var overlayBack: OnBackPressedCallback
    private val fileChooser: PanelFileChooser = PanelFileChooser { intent ->
        fileChooserLaunch.launch(intent)
    }
    private val safTree = registerForActivityResult(OpenDocumentTree()) { uri ->
        folderPicker.onSafTree(uri)
    }
    private val fileChooserLaunch: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        fileChooser.onActivityResult(result)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshRuntimeBinding()
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            refreshRuntimeBinding()
            handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        btnRetry = findViewById(R.id.btnRetry)
        btnRetry.setOnClickListener { onRetry() }

        folderPicker = FolderPicker(this, RuntimePaths(this), { safTree.launch(null) })
        overlaySidechat = OverlaySidechat(this)
        overlaySidechat.onDismiss = { overlayBack.isEnabled = false }
        overlayBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                overlaySidechat.dismiss()
                isEnabled = overlaySidechat.isOpen()
            }
        }
        onBackPressedDispatcher.addCallback(this, overlayBack)
        configureQuestWebView(webView, ::completeBridge, mainPanel = true)

        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(NodeRuntimeService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        showPlaceholder(getString(R.string.runtime_starting), null)
        if (hasNotifPermission()) {
            startRuntime()
            primeMic()
        } else if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasNotifPermission()) {
            startRuntime()
        }
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        ptt.stop()
        if (loadedLoopback && !uiDead) {
            webView.evaluateJavascript(
                "window.__grokQuestPttPaused && window.__grokQuestPttPaused()",
                null,
            )
            if (overlaySidechat.isOpen()) {
                overlaySidechat.webView.evaluateJavascript(
                    "window.__grokQuestPttPaused && window.__grokQuestPttPaused()",
                    null,
                )
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        uiDead = true
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
        ptt.stop()
        overlaySidechat.destroy()
        fileChooser.cancelPending()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun onRetry() {
        if (!hasNotifPermission()) {
            if (Build.VERSION.SDK_INT >= 33) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            return
        }
        loadedLoopback = false
        boundPort = -1
        showPlaceholder(getString(R.string.runtime_starting), null)
        startRuntime()
    }

    private fun hasNotifPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startRuntime() {
        val i = Intent(this, NodeRuntimeService::class.java).setAction(NodeRuntimeService.ACTION_START)
        ContextCompat.startForegroundService(this, i)
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun primeMic() {
        if (micPrimed) return
        micPrimed = true
        if (hasMicPermission() || micRequestInFlight) return
        micRequestInFlight = true
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestMicForPtt() {
        if (micRequestInFlight) return
        micRequestInFlight = true
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun refreshRuntimeBinding() {
        val notifDenied = !hasNotifPermission()
        io.execute {
            val paths = RuntimePaths(this)
            val snapshot = BindSnapshot(
                notifDenied = notifDenied,
                port = readPort(paths),
                health = healthOk(paths),
                serviceState = readServiceState(paths),
                detail = readServiceDetail(paths),
            )
            handler.post { applyBinding(snapshot) }
        }
    }

    private fun applyBinding(s: BindSnapshot) {
        if (uiDead || isDestroyed || isFinishing) return
        if (s.notifDenied) {
            loadedLoopback = false
            boundPort = -1
            showPlaceholder(
                getString(R.string.runtime_denied),
                getString(R.string.perm_notifications_required),
            )
            btnRetry.visibility = View.VISIBLE
            return
        }
        if (s.health && s.port > 0) {
            btnRetry.visibility = View.GONE
            if (!loadedLoopback || boundPort != s.port) {
                boundPort = s.port
                loadedLoopback = true
                placeholderReady = false
                webView.loadUrl("http://127.0.0.1:${s.port}/")
            }
            return
        }
        loadedLoopback = false
        boundPort = -1
        val failed = s.serviceState in ERROR_STATES
        val status = when {
            failed && s.detail.isNotBlank() -> "${getString(R.string.runtime_error)} ${s.detail}"
            failed -> getString(R.string.runtime_down)
            else -> getString(R.string.runtime_starting)
        }
        showPlaceholder(status, null)
        btnRetry.visibility = if (failed) View.VISIBLE else View.GONE
    }

    private fun showPlaceholder(status: String, setup: String?) {
        val payload = JSONObject().put("status", status)
        if (!setup.isNullOrEmpty()) payload.put("setup", setup)
        pendingPlaceholder = payload
        val onPlaceholder = webView.url?.startsWith(PLACEHOLDER_URL) == true
        if (!onPlaceholder) {
            placeholderReady = false
            webView.loadUrl(PLACEHOLDER_URL)
        } else if (placeholderReady) {
            applyPendingPlaceholder()
        }
    }

    private fun applyPendingPlaceholder() {
        val payload = pendingPlaceholder ?: return
        webView.evaluateJavascript("setPanelState($payload)", null)
    }

    private fun openAuthTab(url: String): Boolean {
        if (!AuthTabLauncher.isSafeExternalUrl(url)) return false
        handler.post { AuthTabLauncher.open(this, url) }
        return true
    }

    private fun applyThemePrefFromBridge(pref: String) {
        val mode = when (pref) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        handler.post {
            if (uiDead || isDestroyed || isFinishing) return@post
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    private fun copyTextToClipboard(text: String): Boolean {
        if (text.isBlank()) return false
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Grok sign-in", text))
        return true
    }

    private fun openFolderPicker(id: String) {
        handler.post {
            if (uiDead || isDestroyed || isFinishing) {
                completeBridge(id, "picker closed", null)
                return@post
            }
            folderPicker.show { path -> completeBridge(id, null, path) }
        }
    }

    private fun startPttFromBridge(id: String, argsJson: String) {
        val sessionId = try {
            JSONObject(argsJson).optString("sessionId", "")
        } catch (_: Exception) {
            ""
        }
        handler.post {
            if (uiDead || isDestroyed || isFinishing) {
                completeBridge(id, "activity gone", false)
                return@post
            }
            if (!PttAudioRecord.isSafeSessionId(sessionId)) {
                completeBridge(id, "invalid session", false)
                return@post
            }
            if (boundPort <= 0) {
                completeBridge(id, "runtime not ready", false)
                return@post
            }
            if (!hasMicPermission()) {
                pendingPttStart = id to sessionId
                requestMicForPtt()
                return@post
            }
            beginPtt(id, sessionId)
        }
    }

    private fun stopPttFromBridge(id: String) {
        handler.post {
            ptt.stop()
            completeBridge(id, null, true)
        }
    }

    private fun beginPtt(id: String, sessionId: String) {
        if (uiDead || isDestroyed || isFinishing) {
            completeBridge(id, "activity gone", false)
            return
        }
        val err = ptt.start(boundPort, sessionId)
        if (err != null) completeBridge(id, err, false) else completeBridge(id, null, true)
    }

    private fun shareFileFromBridge(id: String, argsJson: String) {
        val payload = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            completeBridge(id, "invalid share payload", false)
            return
        }
        handler.post {
            if (uiDead || isDestroyed || isFinishing) {
                completeBridge(id, "activity gone", false)
                return@post
            }
            val ok = try {
                PanelShare.shareFromBridge(this, payload)
            } catch (_: Exception) {
                false
            }
            if (ok) completeBridge(id, null, true) else completeBridge(id, "share failed", false)
        }
    }

    private fun onWebViewDownload(url: String?, contentDisposition: String?, mimeType: String?) {
        if (url.isNullOrBlank() || uiDead || isDestroyed || isFinishing) return
        if (url.startsWith("blob:")) {
            val name = PanelShare.sanitizeFilename(
                URLUtil.guessFileName(url, contentDisposition, mimeType),
                "download",
            )
            val mime = mimeType ?: ""
            val js =
                "(function(){fetch(${JSONObject.quote(url)}).then(function(r){return r.blob();})" +
                    ".then(function(b){if(b.size>${PanelShare.MAX_BRIDGE_BYTES})throw new Error('too large');" +
                    "return new Promise(function(resolve,reject){var r=new FileReader();r.onload=function(){resolve(r.result)};" +
                    "r.onerror=reject;r.readAsDataURL(b);});}).then(function(dataUrl){" +
                    "GrokAndroid.shareFile('dl-'+Date.now(),JSON.stringify({filename:${JSONObject.quote(name)}," +
                    "mimeType:${JSONObject.quote(mime)},dataUrl:dataUrl}));}).catch(function(){});})();"
            webView.evaluateJavascript(js, null)
            return
        }
        if (url.startsWith("data:")) {
            val name = PanelShare.sanitizeFilename(
                URLUtil.guessFileName(url, contentDisposition, mimeType),
                "download",
            )
            val ok = PanelShare.shareDataUrl(this, url, name, mimeType ?: "")
            if (!ok) Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val port = boundPort
        if (!PanelShare.isLoopbackHttp(url, port)) return
        io.execute {
            val downloaded = PanelShare.downloadLoopback(url, contentDisposition, mimeType, port)
            handler.post {
                if (uiDead || isDestroyed || isFinishing) return@post
                val ok = downloaded != null &&
                    PanelShare.shareBytes(this, downloaded.bytes, downloaded.filename, downloaded.mimeType)
                if (!ok) Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isAllowedPanelUrl(uri: Uri): Boolean {
        if (uri.scheme == "file" && (uri.path ?: "").startsWith("/android_asset")) return true
        val host = uri.host ?: return false
        if (host != "127.0.0.1" && host != "localhost") return false
        if (uri.scheme != "http") return false
        return boundPort > 0 && uri.port == boundPort
    }

    private fun isLoopbackHttp(url: String): Boolean {
        return url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")
    }

    private fun grokPreloadJs(): String {
        val workspace = JSONObject.quote(RuntimePaths(this).workspace.absolutePath)
        return """
(function () {
  const pending = new Map();
  function call(method, args) {
    const id = String(Date.now()) + "-" + Math.random().toString(16).slice(2);
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      GrokAndroid[method](id, JSON.stringify(args || {}));
    });
  }
  window.grokDesktop = {
    isElectron: true,
    isQuest: true,
    __resolve: function (id, err, value) {
      const p = pending.get(id);
      if (!p) return;
      pending.delete(id);
      if (err) p.reject(new Error(err));
      else p.resolve(value);
    },
    getApiInfo: () => Promise.resolve({ url: location.origin, port: Number(location.port) }),
    openExternal: (url) => call("openExternal", { url: url || "" }),
    copyText: (text) => call("copyText", { text: text || "" }),
    pickFolder: (defaultPath) => call("pickFolder", { defaultPath: defaultPath || "" }),
    setTheme: (pref) => call("setTheme", { pref: pref }),
    openSidechat: (payload) => call("openSidechat", payload || {}),
    getSidechatInit: (nonce) => call("getSidechatInit", { nonce: nonce }),
    startPtt: (payload) => call("startPtt", payload || {}),
    stopPtt: () => call("stopPtt", {}),
    shareFile: (payload) => call("shareFile", payload || {}),
  };
  window.__grokQuestWorkspace = $workspace;
})();
        """.trimIndent()
    }

    fun completeBridge(id: String, err: String?, value: Any?) {
        completeOn(webView, id, err, value)
    }

    private fun overlayComplete(id: String, err: String?, value: Any?) {
        completeOn(overlaySidechat.webView, id, err, value)
    }

    private fun completeOn(target: WebView, id: String, err: String?, value: Any?) {
        val errJs = if (err == null) "null" else JSONObject.quote(err)
        val valueJs = when (value) {
            null -> "null"
            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            is JSONObject -> value.toString()
            else -> JSONObject.wrap(value)?.toString() ?: "null"
        }
        val js = "grokDesktop.__resolve(" + JSONObject.quote(id) + ", " + errJs + ", " + valueJs + ")"
        handler.post {
            if (uiDead || isDestroyed || isFinishing) return@post
            try {
                target.evaluateJavascript(js, null)
            } catch (_: Exception) {
            }
        }
    }

    private fun configureQuestWebView(
        target: WebView,
        complete: (String, String?, Any?) -> Unit,
        mainPanel: Boolean,
    ) {
        val settings = target.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.setSupportMultipleWindows(false)
        target.setBackgroundColor(ContextCompat.getColor(this, R.color.grok_bg))
        target.addJavascriptInterface(
            GrokJsBridge(
                complete,
                ::openAuthTab,
                ::copyTextToClipboard,
                ::openFolderPicker,
                ::shareFileFromBridge,
                ::startPttFromBridge,
                ::stopPttFromBridge,
                ::openSidechatFromBridge,
                ::getSidechatInitFromBridge,
                ::applyThemePrefFromBridge,
            ),
            "GrokAndroid",
        )
        target.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val msg = consoleMessage ?: return true
                Log.i(TAG_WEB, "${msg.message()} -- ${msg.sourceId()}:${msg.lineNumber()}")
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (uiDead || isDestroyed || isFinishing) {
                    filePathCallback?.onReceiveValue(null)
                    return true
                }
                return fileChooser.onShowFileChooser(filePathCallback, fileChooserParams)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val origin = request.origin
                val resources = request.resources ?: emptyArray()
                val loopback = origin != null && isAllowedPanelUrl(origin)
                val micOk = hasMicPermission()
                val wantsMic = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (loopback && micOk && wantsMic) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    request.deny()
                }
            }
        }
        target.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            onWebViewDownload(url, contentDisposition, mimeType)
        }
        target.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (isAllowedPanelUrl(uri)) return false
                val href = uri.toString()
                if (AuthTabLauncher.isSafeExternalUrl(href)) {
                    AuthTabLauncher.open(this@MainActivity, href)
                    return true
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null && isLoopbackHttp(url)) {
                    view.evaluateJavascript(grokPreloadJs(), null)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (mainPanel && url != null && url.startsWith(PLACEHOLDER_URL)) {
                    placeholderReady = true
                    applyPendingPlaceholder()
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError?,
            ) {
                if (!mainPanel || !request.isForMainFrame) return
                loadedLoopback = false
                boundPort = -1
                showPlaceholder(getString(R.string.runtime_error), null)
                btnRetry.visibility = View.VISIBLE
            }
        }
    }

    private fun openSidechatFromBridge(id: String, argsJson: String) {
        val payload = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            JSONObject()
        }
        handler.post {
            if (uiDead || isDestroyed || isFinishing) {
                completeBridge(id, "activity gone", false)
                return@post
            }
            if (boundPort <= 0) {
                completeBridge(id, "runtime not ready", false)
                return@post
            }
            val nonce = overlaySidechat.putInit(payload)
            val url = "http://127.0.0.1:$boundPort/?side=$nonce"
            overlaySidechat.showUrl(url) { wv ->
                configureQuestWebView(wv, ::overlayComplete, mainPanel = false)
            }
            overlayBack.isEnabled = true
            val ok = JSONObject().put("ok", true)
            completeBridge(id, null, ok)
            overlayComplete(id, null, ok)
        }
    }

    private fun getSidechatInitFromBridge(id: String, argsJson: String) {
        val nonce = try {
            JSONObject(argsJson).optString("nonce", "")
        } catch (_: Exception) {
            ""
        }
        val payload = overlaySidechat.takeInit(nonce)
        overlayComplete(id, null, payload)
    }

    private fun readPort(paths: RuntimePaths): Int {
        return try {
            if (!paths.runtimeJson.isFile) return -1
            JSONObject(paths.runtimeJson.readText()).optInt("port", -1)
        } catch (_: Exception) {
            -1
        }
    }

    private fun readServiceState(paths: RuntimePaths): String {
        return try {
            if (!paths.serviceStatus.isFile) return ""
            JSONObject(paths.serviceStatus.readText()).optString("state", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun readServiceDetail(paths: RuntimePaths): String {
        return try {
            if (!paths.serviceStatus.isFile) return ""
            JSONObject(paths.serviceStatus.readText()).optString("detail", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun healthOk(paths: RuntimePaths): Boolean {
        val port = readPort(paths)
        if (port <= 0) return false
        return try {
            val conn = URL("http://127.0.0.1:$port/api/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private data class BindSnapshot(
        val notifDenied: Boolean,
        val port: Int,
        val health: Boolean,
        val serviceState: String,
        val detail: String,
    )
}
