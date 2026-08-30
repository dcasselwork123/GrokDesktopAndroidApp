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
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
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

    private val io = Executors.newCachedThreadPool()
    private val startedForeground = AtomicBoolean(false)
    private var nodeProcess: Process? = null
    private var nodePid: Int = -1
    private var wakeLock: PowerManager.WakeLock? = null
    private var logPump: Thread? = null
    @Volatile private var notificationText: String = "Starting…"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground(notificationText)
        when (intent?.action) {
            ACTION_STOP -> {
                stopRuntime("stopped")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_WX -> {
                io.execute { runWx() }
                return START_STICKY
            }
            else -> {
                io.execute { startRuntime() }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopRuntime("destroyed")
        io.shutdownNow()
        super.onDestroy()
    }

    private fun startRuntime() {
        val paths = RuntimePaths(this)
        paths.ensureDirs()
        extractQuestEntry(paths)
        runWx()

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
        if (existing > 0 && pidAlive(existing)) {
            nodePid = existing
            notificationText = portLabel(paths) ?: "Runtime (adopted pid $existing)"
            enterForeground(notificationText)
            writeStatus("running", "adopted existing Node pid $existing")
            appendLog(paths, "adopted pid $existing")
            return
        }

        try {
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
            acquireWakeLock()
            notificationText = "Starting…"
            enterForeground(notificationText)
            writeStatus("starting", "spawned wrap pid $nodePid")
            appendLog(paths, "spawned libnodewrap pid=$nodePid")
            pumpLogs(paths, proc)
            watchHandshake(paths)
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            writeStatus("error", t.toString())
            enterForeground("Start failed")
        }
    }

    private fun extractQuestEntry(paths: RuntimePaths) {
        val dest = paths.questEntry
        dest.parentFile?.mkdirs()
        assets.open("grok-desktop/server/questEntry.js").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
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
        logPump = Thread({
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
                if (label != null) {
                    notificationText = label
                    enterForeground(label)
                    writeStatus("running", label)
                    return@execute
                }
                if (nodeProcess?.isAlive == false) {
                    writeStatus("error", "Node exited before handshake")
                    return@execute
                }
                try {
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                    return@execute
                }
            }
            writeStatus("running", "handshake timeout; see spike-results.json")
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

    private fun stopRuntime(reason: String) {
        val pid = nodePid
        val proc = nodeProcess
        if (pid > 0) {
            killProcessGroup(pid)
        }
        // Group kill only — never Process.killProcess (that is not a pgid kill).
        try {
            proc?.waitFor(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        nodeProcess = null
        nodePid = -1
        releaseWakeLock()
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
        }
        val proc = nodeProcess
        val died = try {
            proc?.waitFor(3, TimeUnit.SECONDS) == true
        } catch (_: InterruptedException) {
            false
        }
        if (!died && (proc?.isAlive == true || pidAlive(pid))) {
            try {
                Os.kill(-pid, OsConstants.SIGKILL)
            } catch (e: ErrnoException) {
                Log.w(TAG, "SIGKILL -$pid: ${e.message}")
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

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grokdesktop:runtime").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
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
        if (!startedForeground.getAndSet(true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
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
