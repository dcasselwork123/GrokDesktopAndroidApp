package dev.grokdesktop.quest

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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
            val chmodOk = copy.setExecutable(true, false)
            if (!chmodOk) {
                Os.chmod(
                    copy.absolutePath,
                    OsConstants.S_IRUSR or OsConstants.S_IWUSR or OsConstants.S_IXUSR,
                )
            }
            copyNote = exec(
                name = "filesDir copy of libnode.so --version (must EACCES)",
                executable = copy,
                args = listOf("--version"),
                ldLibraryPath = paths.nativeLibraryDir.absolutePath,
                expectOk = false,
            )
            copyNote.put("chmodOk", true)
        } catch (e: ErrnoException) {
            copyNote.put("name", "filesDir copy of libnode.so")
            copyNote.put("error", "chmod +x failed: ${e.message}")
            copyNote.put("chmodOk", false)
            copyNote.put("pass", false)
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
            val stdout = StringBuilder()
            val reader = Thread({
                try {
                    proc.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(256)
                        while (stdout.length < 8000) {
                            val n = r.read(buf)
                            if (n < 0) break
                            stdout.append(buf, 0, n)
                        }
                    }
                } catch (_: Exception) {
                }
            }, "wx-stdout").apply { isDaemon = true; start() }
            val finished = proc.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
            }
            try {
                reader.join(500)
            } catch (_: InterruptedException) {
            }
            val code = if (finished) proc.exitValue() else -1
            result.put("ok", finished && code == 0)
            result.put("exitCode", code)
            result.put("stdout", stdout.toString().take(4000))
            result.put("timedOut", !finished)
            result.put("eacces", false)
            if (expectOk) {
                result.put("pass", finished && code == 0)
            } else {
                result.put("pass", false)
                result.put("error", "W^X control failed: filesDir exec was allowed")
            }
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
