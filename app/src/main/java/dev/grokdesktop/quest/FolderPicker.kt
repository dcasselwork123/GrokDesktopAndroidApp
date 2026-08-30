package dev.grokdesktop.quest

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * POSIX working-folder picker for Quest. Lists app-owned dirs and optionally
 * one-shot copies a SAF tree into `$HOME/projects/<name>` (no write-back).
 */
class FolderPicker(
    private val activity: AppCompatActivity,
    private val paths: RuntimePaths,
    private val launchSafTree: () -> Unit,
) {
    companion object {
        const val MAX_FILES = 5000
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pending: ((String?) -> Unit)? = null
    private var copyCancel = AtomicBoolean(false)
    private var progressDialog: AlertDialog? = null

    fun show(onResult: (String?) -> Unit) {
        paths.ensureDirs()
        pending = onResult
        val entries = listEntries()
        val labels = entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.folder_picker_title)
            .setItems(labels) { _, which ->
                finish(entries.getOrNull(which)?.path)
            }
            .setNeutralButton(R.string.folder_picker_create) { _, _ ->
                promptCreateProject()
            }
            .setNegativeButton(R.string.folder_picker_import) { _, _ ->
                launchSafTree()
            }
            .setPositiveButton(android.R.string.cancel) { _, _ ->
                finish(null)
            }
            .setOnCancelListener { finish(null) }
            .show()
    }

    fun onSafTree(uri: Uri?) {
        if (uri == null) {
            finish(null)
            return
        }
        try {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {
            /* one-shot copy does not require persistable grants */
        }
        val tree = DocumentFile.fromTreeUri(activity, uri)
        if (tree == null || !tree.isDirectory) {
            toast(activity.getString(R.string.folder_picker_import_failed))
            finish(null)
            return
        }
        val name = sanitizeProjectName(tree.name ?: "import")
        val dest = uniqueDir(paths.projects, name)
        copyCancel = AtomicBoolean(false)
        showProgress()
        io.execute {
            try {
                dest.mkdirs()
                val stats = Stats()
                copyTree(tree, dest, stats)
                main.post {
                    dismissProgress()
                    if (copyCancel.get()) {
                        dest.deleteRecursively()
                        finish(null)
                    } else {
                        finish(dest.absolutePath)
                    }
                }
            } catch (t: Throwable) {
                dest.deleteRecursively()
                main.post {
                    dismissProgress()
                    val msg = when (t) {
                        is CapExceeded -> activity.getString(R.string.folder_picker_cap)
                        is Cancelled -> null
                        else -> t.message ?: activity.getString(R.string.folder_picker_import_failed)
                    }
                    if (msg != null) toast(msg)
                    finish(null)
                }
            }
        }
    }

    fun cancelCopy() {
        copyCancel.set(true)
    }

    private data class Entry(val label: String, val path: String)

    private fun listEntries(): List<Entry> {
        val out = mutableListOf<Entry>()
        out += Entry(
            activity.getString(R.string.folder_picker_workspace),
            paths.workspace.absolutePath,
        )
        fun addChildren(root: File, prefix: String) {
            val kids = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }
                ?: return
            for (d in kids) {
                out += Entry("$prefix / ${d.name}", d.absolutePath)
            }
        }
        addChildren(paths.projects, activity.getString(R.string.folder_picker_projects))
        addChildren(paths.visibleProjects, activity.getString(R.string.folder_picker_visible))
        return out
    }

    private fun promptCreateProject() {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = activity.getString(R.string.folder_picker_name_hint)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.folder_picker_create)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = sanitizeProjectName(input.text?.toString().orEmpty())
                val dest = uniqueDir(paths.projects, name)
                dest.mkdirs()
                finish(dest.absolutePath)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish(null) }
            .setOnCancelListener { finish(null) }
            .show()
    }

    private fun showProgress() {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        val label = TextView(activity).apply {
            id = android.R.id.message
            text = activity.getString(R.string.folder_picker_copying)
        }
        box.addView(label)
        box.addView(bar)
        progressDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.folder_picker_import)
            .setView(box)
            .setNegativeButton(android.R.string.cancel) { _, _ -> copyCancel.set(true) }
            .setCancelable(false)
            .show()
    }

    private fun dismissProgress() {
        try {
            progressDialog?.dismiss()
        } catch (_: Exception) {
        }
        progressDialog = null
    }

    private class Stats {
        var files: Int = 0
        var bytes: Long = 0
    }

    private class CapExceeded : RuntimeException()
    private class Cancelled : RuntimeException()

    private fun copyTree(src: DocumentFile, destDir: File, stats: Stats) {
        if (copyCancel.get()) throw Cancelled()
        destDir.mkdirs()
        for (child in src.listFiles()) {
            if (copyCancel.get()) throw Cancelled()
            val name = sanitizeFileName(child.name ?: if (child.isDirectory) "folder" else "file")
            if (child.isDirectory) {
                copyTree(child, File(destDir, name), stats)
            } else {
                stats.files += 1
                val len = child.length().coerceAtLeast(0L)
                stats.bytes += len
                if (stats.files > MAX_FILES || stats.bytes > MAX_BYTES) throw CapExceeded()
                val out = File(destDir, name)
                activity.contentResolver.openInputStream(child.uri)?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("open failed: ${child.uri}")
                if (stats.files % 25 == 0) {
                    val n = stats.files
                    main.post {
                        progressDialog?.findViewById<TextView>(android.R.id.message)?.text =
                            activity.getString(R.string.folder_picker_copying_n, n)
                    }
                }
            }
        }
    }

    private fun finish(path: String?) {
        val cb = pending ?: return
        pending = null
        cb(path)
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
    }
}

internal fun sanitizeProjectName(raw: String): String {
    val s = raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.')
    return s.take(64).ifEmpty { "project" }
}

internal fun sanitizeFileName(raw: String): String {
    val s = raw.replace(Regex("[\\\\/]+"), "-").trim()
    return s.take(180).ifEmpty { "file" }
}

internal fun uniqueDir(root: File, name: String): File {
    root.mkdirs()
    var dest = File(root, name)
    var n = 2
    while (dest.exists()) {
        dest = File(root, "$name-$n")
        n += 1
    }
    return dest
}
