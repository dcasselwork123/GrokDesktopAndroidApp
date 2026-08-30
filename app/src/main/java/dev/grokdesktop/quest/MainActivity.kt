package dev.grokdesktop.quest

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat


class MainActivity : AppCompatActivity() {
    private lateinit var dashboard: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startRuntime()
        } else {
            Toast.makeText(this, R.string.perm_notifications_required, Toast.LENGTH_LONG).show()
            render()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        dashboard = findViewById(R.id.dashboard)
        findViewById<Button>(R.id.btnStart).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopRuntime() }
        findViewById<Button>(R.id.btnWx).setOnClickListener { rerunWx() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyResults() }
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(NodeRuntimeService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    private fun onStartClicked() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startRuntime()
    }

    private fun startRuntime() {
        val i = Intent(this, NodeRuntimeService::class.java).setAction(NodeRuntimeService.ACTION_START)
        ContextCompat.startForegroundService(this, i)
    }

    private fun stopRuntime() {
        val i = Intent(this, NodeRuntimeService::class.java).setAction(NodeRuntimeService.ACTION_STOP)
        startService(i)
    }

    private fun rerunWx() {
        val paths = RuntimePaths(this)
        paths.ensureDirs()
        Thread {
            WxProbe.run(paths)
            handler.post { render() }
        }.start()
    }

    private fun copyResults() {
        val text = dashboard.text.toString()
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("spike", text))
        Toast.makeText(this, "Copied spike dump", Toast.LENGTH_SHORT).show()
    }

    private fun render() {
        val paths = RuntimePaths(this)
        val sb = StringBuilder()
        sb.appendLine("package: $packageName")
        sb.appendLine("nativeLibraryDir: ${paths.nativeLibraryDir}")
        sb.appendLine("  libnodewrap.so: ${paths.wrap.isFile} ${paths.wrap}")
        sb.appendLine("  libnode.so:     ${paths.node.isFile} ${paths.node}")
        sb.appendLine("  libgrok.so:     ${paths.grok.isFile} ${paths.grok}")
        sb.appendLine("  libc++_shared:  ${paths.cxx.isFile}")
        sb.appendLine("filesDir: ${paths.filesDir}")
        sb.appendLine("HOME: ${paths.home}")
        sb.appendLine("questEntry: ${paths.questEntry.isFile} ${paths.questEntry}")
        sb.appendLine()
        sb.appendLine("== service-status ==")
        sb.appendLine(readFile(paths.serviceStatus))
        sb.appendLine()
        sb.appendLine("== node.pid ==")
        sb.appendLine(readFile(paths.pidFile).ifBlank { "(none)" })
        sb.appendLine()
        sb.appendLine("== runtime.json ==")
        sb.appendLine(readFile(paths.runtimeJson).ifBlank { "(none — Node has not bound yet)" })
        sb.appendLine()
        sb.appendLine("== W^X ==")
        sb.appendLine(readFile(paths.wxResults).ifBlank { "(not run)" })
        sb.appendLine()
        sb.appendLine("== spike-results.json ==")
        sb.appendLine(readFile(paths.spikeResults).ifBlank { "(none)" })
        sb.appendLine()
        sb.appendLine("== debug.log (tail) ==")
        sb.appendLine(tail(paths.debugLog, 80))
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                sb.appendLine()
                sb.appendLine(getString(R.string.perm_notifications_required))
            }
        }
        dashboard.text = sb.toString()
    }

    private fun readFile(f: java.io.File): String {
        return try {
            if (f.isFile) f.readText() else ""
        } catch (t: Throwable) {
            t.toString()
        }
    }

    private fun tail(f: java.io.File, lines: Int): String {
        val text = readFile(f)
        if (text.isBlank()) return "(empty)"
        val all = text.lines()
        return all.takeLast(lines).joinToString("\n")
    }
}
