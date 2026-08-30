package dev.grokdesktop.quest

import android.content.Context
import org.json.JSONObject
import java.io.File

/** App-private POSIX layout used by Node ($HOME) and the spike dashboard. */
class RuntimePaths(ctx: Context) {
    val filesDir: File = ctx.filesDir
    val nativeLibraryDir: File = File(ctx.applicationInfo.nativeLibraryDir)
    val wrap: File = File(nativeLibraryDir, "libnodewrap.so")
    val node: File = File(nativeLibraryDir, "libnode.so")
    val grok: File = File(nativeLibraryDir, "libgrok.so")
    val cxx: File = File(nativeLibraryDir, "libc++_shared.so")
    val home: File = File(filesDir, "home")
    val workspace: File = File(home, "workspace")
    val tmp: File = File(filesDir, "tmp")
    val grokHome: File = File(home, ".grok")
    val desktopDir: File = File(home, ".grok-desktop")
    val appJs: File = File(filesDir, "app")
    val questEntry: File = File(appJs, "server/questEntry.js")
    val pidFile: File = File(desktopDir, "node.pid")
    val runtimeJson: File = File(desktopDir, "runtime.json")
    val spikeResults: File = File(desktopDir, "spike-results.json")
    val wxResults: File = File(desktopDir, "wx-results.json")
    val debugLog: File = File(desktopDir, "debug.log")
    val serviceStatus: File = File(desktopDir, "service-status.json")

    fun ensureDirs() {
        home.mkdirs()
        workspace.mkdirs()
        tmp.mkdirs()
        grokHome.mkdirs()
        desktopDir.mkdirs()
        File(appJs, "server").mkdirs()
        File(appJs, "renderer").mkdirs()
        val config = File(desktopDir, "config.json")
        if (!config.exists()) {
            config.writeText(
                JSONObject()
                    .put("lastCwd", workspace.absolutePath)
                    .toString(),
            )
        }
    }
}
