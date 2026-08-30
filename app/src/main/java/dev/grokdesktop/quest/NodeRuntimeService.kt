package dev.grokdesktop.quest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NodeRuntimeService : Service() {
    companion object {
        const val ACTION_START = "dev.grokdesktop.quest.START"
        const val ACTION_STOP = "dev.grokdesktop.quest.STOP"
        const val ACTION_WX = "dev.grokdesktop.quest.WX"
        const val ACTION_STATUS = "dev.grokdesktop.quest.RUNTIME_STATUS"
        const val EXTRA_STATUS = "status"
        private const val TAG = "GrokRuntime"
        private const val NOTIF_ID = 7
        private const val CHANNEL_ID = "grok-runtime"
    }

    private val runtimeOps = Executors.newSingleThreadExecutor()
    private val io = Executors.newCachedThreadPool()
    private val wakeScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val startedForeground = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private var nodeProcess: Process? = null
    private var nodePid: Int = -1
    @Volatile private var notificationText: String = "Starting…"
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var lastLiveAt: Long = 0
    @Volatile private var sawLive: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // 404 / connection failure must not hold the lock; no run.lock file.
        wakeScheduler.scheduleAtFixedRate({
            try {
                pollRunsForWakeLock()
            } catch (t: Throwable) {
                Log.w(TAG, "wake poll: ${t.message}")
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground(notificationText)
        when (intent?.action) {
            ACTION_STOP -> {
                val stopId = startId
                runtimeOps.execute {
                    stopRuntime("stopped")
                    // Only if this STOP is still the latest onStartCommand.
                    // A START delivered after this STOP keeps the instance.
                    stopSelf(stopId)
                }
                return START_NOT_STICKY
            }
            ACTION_WX -> {
                io.execute { runWx() }
                return if (nodePid > 0) START_STICKY else START_NOT_STICKY
            }
            else -> {
                runtimeOps.execute { startRuntime() }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopRuntime("destroyed")
        wakeScheduler.shutdownNow()
        releaseWakeLock()
        runtimeOps.shutdownNow()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun startRuntime() {
        val paths = RuntimePaths(this)
        paths.ensureDirs()

        if (!paths.wrap.isFile || !paths.node.isFile) {
            writeStatus(
                "error",
                "Missing libnodewrap.so or libnode.so in nativeLibraryDir. Run scripts/fetch-runtime.ps1 before assemble.",
            )
            enterForeground("Missing native binaries")
            return
        }

        if (nodeProcess?.isAlive == true && nodePid > 0) {
            writeStatus("running", "already running pid $nodePid")
            enterForeground(portLabel(paths) ?: notificationText)
            return
        }
        val existing = readPid(paths)
        if (existing > 0 && pidAlive(existing) && healthOk(paths)) {
            nodePid = existing
            notificationText = portLabel(paths) ?: "Runtime (adopted pid $existing)"
            enterForeground(notificationText)
            writeStatus("running", "adopted existing Node pid $existing")
            appendLog(paths, "adopted pid $existing")
            return
        }
        if (!starting.compareAndSet(false, true)) {
            writeStatus("starting", "start already in flight")
            return
        }
        try {
            extractAppTree(paths)
            val httpApi = File(paths.appJs, "server/httpApi.js")
            if (!httpApi.isFile || !paths.questEntry.isFile) {
                writeStatus(
                    "error",
                    "Missing vendored JS (httpApi.js / questEntry.js). Run scripts/sync-desktop.ps1 before assemble.",
                )
                enterForeground("Missing JS assets")
                return
            }
            val pb = ProcessBuilder(
                paths.wrap.absolutePath,
                paths.node.absolutePath,
                paths.questEntry.absolutePath,
            )
            pb.directory(paths.appJs)
            pb.redirectErrorStream(true)
            pb.environment().apply {
                put("HOME", paths.home.absolutePath)
                put("TMPDIR", paths.tmp.absolutePath)
                put("GROK_HOME", paths.grokHome.absolutePath)
                put("GROK_BIN", paths.grok.absolutePath)
                put("GROK_QUEST_WORKSPACE", paths.workspace.absolutePath)
                put("GROK_DESKTOP_HOST", "127.0.0.1")
                put("GROK_DESKTOP_PORT", "3847")
                put("GROK_DESKTOP_ALLOW_LAN", "0")
                put("SHELL", "/system/bin/sh")
                put("PATH", "/system/bin:/system/xbin")
                put("LD_LIBRARY_PATH", paths.nativeLibraryDir.absolutePath)
                put("TERM", "xterm-256color")
            }
            val proc = pb.start()
            nodeProcess = proc
            nodePid = pidOf(proc)
            paths.pidFile.writeText(nodePid.toString())
            notificationText = "Starting…"
            enterForeground(notificationText)
            writeStatus("starting", "spawned wrap pid $nodePid")
            appendLog(paths, "spawned libnodewrap pid=$nodePid")
            pumpLogs(paths, proc)
            watchHandshake(paths)
            io.execute { runWx() }
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            writeStatus("error", t.toString())
            enterForeground("Start failed")
        } finally {
            starting.set(false)
        }
    }

    private fun extractAppTree(paths: RuntimePaths) {
        val stampFile = File(paths.appJs, ".extract-stamp")
        val sourceRev = try {
            assets.open("grok-desktop/SOURCE_REV").bufferedReader().use { it.readText().trim() }
        } catch (_: Exception) {
            ""
        }
        val stamp = "${BuildConfig.VERSION_CODE}\n${BuildConfig.VERSION_NAME}\n$sourceRev"
        val httpApi = File(paths.appJs, "server/httpApi.js")
        if (
            stampFile.isFile &&
            stampFile.readText() == stamp &&
            paths.questEntry.isFile &&
            httpApi.isFile
        ) {
            return
        }
        File(paths.appJs, "server").deleteRecursively()
        File(paths.appJs, "renderer").deleteRecursively()
        copyAssetTree("grok-desktop", paths.appJs)
        stampFile.parentFile?.mkdirs()
        stampFile.writeText(stamp)
    }

    private fun copyAssetTree(assetPath: String, dest: File) {
        val children = assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        dest.mkdirs()
        for (name in children) {
            val childAsset = if (assetPath.isEmpty()) name else "$assetPath/$name"
            copyAssetTree(childAsset, File(dest, name))
        }
    }

    private fun runWx() {
        try {
            val json = WxProbe.run(RuntimePaths(this))
            appendLog(RuntimePaths(this), "W^X results written")
            broadcast("wx")
            Log.i(TAG, json.toString())
        } catch (t: Throwable) {
            Log.e(TAG, "W^X probe failed", t)
        }
    }

    private fun pumpLogs(paths: RuntimePaths, proc: Process) {
        Thread({
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        appendLog(paths, line)
                        Log.i(TAG, line)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "log pump: ${t.message}")
            }
            val code = try {
                proc.waitFor()
            } catch (_: InterruptedException) {
                -1
            }
            appendLog(paths, "node exited code=$code")
            if (nodeProcess === proc) {
                writeStatus("exited", "Node exit $code")
                enterForeground("Node exited $code")
            }
        }, "node-log").apply { isDaemon = true; start() }
    }

    private fun watchHandshake(paths: RuntimePaths) {
        io.execute {
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                val label = portLabel(paths)
                if (label != null && healthOk(paths)) {
                    notificationText = label
                    enterForeground(label)
                    writeStatus("running", label)
                    return@execute
                }
                if (nodeProcess?.isAlive == false) {
                    writeStatus("error", "Node exited before handshake")
                    enterForeground("Node exited")
                    return@execute
                }
                try {
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                    return@execute
                }
            }
            writeStatus("handshake-timeout", "no /api/health after 30s; see spike-results.json")
            enterForeground("Handshake timeout")
        }
    }

    private fun portLabel(paths: RuntimePaths): String? {
        if (!paths.runtimeJson.isFile) return null
        return try {
            val port = JSONObject(paths.runtimeJson.readText()).optInt("port", -1)
            if (port > 0) "Runtime on 127.0.0.1:$port" else null
        } catch (_: Exception) {
            null
        }
    }

    private fun healthOk(paths: RuntimePaths): Boolean {
        val port = try {
            if (!paths.runtimeJson.isFile) return false
            JSONObject(paths.runtimeJson.readText()).optInt("port", -1)
        } catch (_: Exception) {
            return false
        }
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

    private fun pollRunsForWakeLock() {
        if (anyRunLive()) {
            sawLive = true
            lastLiveAt = System.currentTimeMillis()
            acquireWakeLock()
            return
        }
        if (sawLive && lastLiveAt > 0 && System.currentTimeMillis() - lastLiveAt >= 60_000L) {
            releaseWakeLock()
        }
    }

    private fun anyRunLive(): Boolean {
        val paths = RuntimePaths(this)
        val port = try {
            if (!paths.runtimeJson.isFile) return false
            JSONObject(paths.runtimeJson.readText()).optInt("port", -1)
        } catch (_: Exception) {
            return false
        }
        if (port <= 0) return false
        return try {
            val conn = URL("http://127.0.0.1:$port/api/runs").openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return false
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            runsIndicateLive(body)
        } catch (_: Exception) {
            false
        }
    }

    private fun runsIndicateLive(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return false
        return try {
            when {
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    (0 until arr.length()).any { i -> runObjLive(arr.optJSONObject(i)) }
                }
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    if (obj.optBoolean("running", false) || obj.optBoolean("live", false)) return true
                    val arr = obj.optJSONArray("runs") ?: obj.optJSONArray("items")
                    if (arr != null) {
                        (0 until arr.length()).any { i -> runObjLive(arr.optJSONObject(i)) }
                    } else {
                        runObjLive(obj)
                    }
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun runObjLive(obj: JSONObject?): Boolean {
        if (obj == null) return false
        if (obj.optBoolean("running", false) || obj.optBoolean("live", false) || obj.optBoolean("active", false)) {
            return true
        }
        val status = obj.optString("status", obj.optString("state", "")).lowercase()
        return status == "running" || status == "live" || status == "in_progress" ||
            status == "streaming" || status == "active" || status == "started"
    }

    private fun acquireWakeLock() {
        val existing = wakeLock
        val wl = if (existing != null) {
            existing
        } else {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dev.grokdesktop.quest:runs").also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        }
        if (!wl.isHeld) {
            @Suppress("DEPRECATION")
            wl.acquire()
        }
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        if (wl.isHeld) {
            wl.release()
        }
    }

    private fun stopRuntime(reason: String) {
        lastLiveAt = 0
        sawLive = false
        releaseWakeLock()
        val pid = nodePid
        if (pid > 0) {
            killProcessGroup(pid)
        }
        nodeProcess = null
        nodePid = -1
        starting.set(false)
        try {
            RuntimePaths(this).pidFile.delete()
        } catch (_: Exception) {
        }
        writeStatus("stopped", reason)
        enterForeground("Stopped")
    }

    private fun killProcessGroup(pid: Int) {
        try {
            Os.kill(-pid, OsConstants.SIGTERM)
        } catch (e: ErrnoException) {
            Log.w(TAG, "SIGTERM -$pid: ${e.message}")
            try {
                Os.kill(pid, OsConstants.SIGTERM)
            } catch (e2: ErrnoException) {
                Log.w(TAG, "SIGTERM $pid: ${e2.message}")
            }
        }
        val proc = nodeProcess
        val died = if (proc != null) {
            try {
                proc.waitFor(3, TimeUnit.SECONDS) == true
            } catch (_: InterruptedException) {
                false
            }
        } else {
            val until = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < until && pidAlive(pid)) {
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    break
                }
            }
            !pidAlive(pid)
        }
        if (!died && pidAlive(pid)) {
            try {
                Os.kill(-pid, OsConstants.SIGKILL)
            } catch (e: ErrnoException) {
                Log.w(TAG, "SIGKILL -$pid: ${e.message}")
                try {
                    Os.kill(pid, OsConstants.SIGKILL)
                } catch (e2: ErrnoException) {
                    Log.w(TAG, "SIGKILL $pid: ${e2.message}")
                }
            }
        }
    }

    private fun pidAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        return try {
            Os.kill(pid, 0)
            true
        } catch (_: ErrnoException) {
            false
        }
    }

    private fun pidOf(proc: Process): Int {
        var c: Class<*>? = proc.javaClass
        while (c != null) {
            try {
                val m = c.methods.firstOrNull { it.name == "pid" && it.parameterCount == 0 }
                if (m != null) {
                    val v = m.invoke(proc)
                    if (v is Int) return v
                    if (v is Long) return v.toInt()
                }
            } catch (_: Exception) {
            }
            try {
                val f = c.getDeclaredField("pid")
                f.isAccessible = true
                val v = f.get(proc)
                if (v is Int) return v
                if (v is Long) return v.toInt()
            } catch (_: Exception) {
            }
            c = c.superclass
        }
        throw IllegalStateException("cannot read child pid")
    }

    private fun readPid(paths: RuntimePaths): Int {
        return try {
            paths.pidFile.readText().trim().toInt()
        } catch (_: Exception) {
            -1
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun enterForeground(text: String) {
        notificationText = text
        val notif = buildNotification(text)
        if (!startedForeground.get()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
            startedForeground.set(true)
        } else {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launch)
            .setColor(ContextCompat.getColor(this, R.color.grok_accent))
            .build()
    }

    private fun writeStatus(state: String, detail: String) {
        val paths = RuntimePaths(this)
        paths.ensureDirs()
        val obj = JSONObject()
            .put("state", state)
            .put("detail", detail)
            .put("pid", nodePid)
            .put("fgs", true)
            .put("notification", notificationText)
            .put("nativeLibraryDir", paths.nativeLibraryDir.absolutePath)
            .put("updatedAt", System.currentTimeMillis())
        paths.serviceStatus.writeText(obj.toString(2))
        broadcast(state)
    }

    private fun appendLog(paths: RuntimePaths, line: String) {
        try {
            paths.debugLog.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    private fun broadcast(state: String) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, state),
        )
    }
}
