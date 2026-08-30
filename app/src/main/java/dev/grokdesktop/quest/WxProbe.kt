package dev.grokdesktop.quest

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * W^X control: exec from nativeLibraryDir must work; the same bytes from filesDir
 * must fail EACCES on targetSdk 34.
 */
object WxProbe {
    private const val TAG = "GrokWx"

    fun run(paths: RuntimePaths): JSONObject {
        paths.ensureDirs()
        val tests = JSONArray()
        tests.put(
            exec(
                name = "nativeLibraryDir/libnode.so --version",
                executable = paths.node,
                args = listOf("--version"),
                ldLibraryPath = paths.nativeLibraryDir.absolutePath,
                expectOk = true,
            ),
        )
        tests.put(
            exec(
                name = "nativeLibraryDir/libgrok.so --version",
                executable = paths.grok,
                args = listOf("--version"),
                ldLibraryPath = paths.nativeLibraryDir.absolutePath,
                expectOk = true,
            ),
        )
        tests.put(
            exec(
                name = "libnodewrap.so libnode.so --version",
                executable = paths.wrap,
                args = listOf(paths.node.absolutePath, "--version"),
                ldLibraryPath = paths.nativeLibraryDir.absolutePath,
                expectOk = true,
            ),
        )

        val copy = File(paths.filesDir, "wx-node-copy")
        var copyNote = JSONObject()
        try {
            paths.node.copyTo(copy, overwrite = true)
            copy.setReadable(true, false)
            copy.setExecutable(true, false)
            copyNote = exec(
                name = "filesDir copy of libnode.so --version (must EACCES)",
                executable = copy,
                args = listOf("--version"),
                ldLibraryPath = paths.nativeLibraryDir.absolutePath,
                expectOk = false,
            )
        } catch (t: Throwable) {
            copyNote.put("name", "filesDir copy of libnode.so")
            copyNote.put("error", t.toString())
            copyNote.put("pass", false)
        }
        tests.put(copyNote)

        val obj = JSONObject()
            .put("nativeLibraryDir", paths.nativeLibraryDir.absolutePath)
            .put("filesDir", paths.filesDir.absolutePath)
            .put("wrapExists", paths.wrap.isFile)
            .put("nodeExists", paths.node.isFile)
            .put("grokExists", paths.grok.isFile)
            .put("cxxExists", paths.cxx.isFile)
            .put("tests", tests)
        paths.wxResults.writeText(obj.toString(2))
        return obj
    }

    private fun exec(
        name: String,
        executable: File,
        args: List<String>,
        ldLibraryPath: String,
        expectOk: Boolean,
    ): JSONObject {
        val result = JSONObject()
            .put("name", name)
            .put("path", executable.absolutePath)
            .put("exists", executable.isFile)
            .put("expectOk", expectOk)
        if (!executable.isFile) {
            result.put("ok", false)
            result.put("error", "missing")
            result.put("pass", false)
            return result
        }
        return try {
            val cmd = ArrayList<String>()
            cmd.add(executable.absolutePath)
            cmd.addAll(args)
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = ldLibraryPath
            val proc = pb.start()
            val stdout = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
            }
            val code = if (finished) proc.exitValue() else -1
            result.put("ok", finished && code == 0)
            result.put("exitCode", code)
            result.put("stdout", stdout.take(4000))
            result.put("timedOut", !finished)
            val pass = if (expectOk) finished && code == 0 else false
            result.put("pass", pass)
            result
        } catch (e: IOException) {
            val msg = e.message ?: e.toString()
            val eacces = msg.contains("Permission denied", ignoreCase = true) ||
                msg.contains("EACCES") ||
                (e.cause?.message?.contains("Permission denied", ignoreCase = true) == true)
            result.put("ok", false)
            result.put("error", msg)
            result.put("eacces", eacces)
            result.put("pass", if (expectOk) false else eacces)
            Log.i(TAG, "$name -> $msg")
            result
        } catch (t: Throwable) {
            result.put("ok", false)
            result.put("error", t.toString())
            result.put("pass", false)
            result
        }
    }
}
