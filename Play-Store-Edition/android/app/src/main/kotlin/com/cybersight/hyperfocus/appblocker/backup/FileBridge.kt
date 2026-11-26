package com.cybersight.hyperfocus.appblocker.backup

import android.app.Activity
import android.content.Intent
import io.flutter.plugin.common.MethodChannel

/**
 * Backup file I/O through the Storage Access Framework (ARCHITECTURE.md §9).
 *
 * SAF rather than a file-picker plugin: the user chooses where the backup lands,
 * no storage permission is requested, and it adds no dependency to an app whose
 * whole claim is that it asks for very little.
 */
class FileBridge(private val activity: Activity) {

    private companion object {
        const val REQUEST_EXPORT = 101
        const val REQUEST_IMPORT = 102
        const val MIME = "application/json"
    }

    private var pending: MethodChannel.Result? = null
    private var payload: String? = null

    fun export(json: String, suggestedName: String, result: MethodChannel.Result) {
        if (!claim(result)) return
        payload = json
        activity.startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(MIME)
                .putExtra(Intent.EXTRA_TITLE, suggestedName),
            REQUEST_EXPORT,
        )
    }

    fun import(result: MethodChannel.Result) {
        if (!claim(result)) return
        activity.startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(MIME),
            REQUEST_IMPORT,
        )
    }

    /** Returns true if this result was ours. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_EXPORT && requestCode != REQUEST_IMPORT) return false
        val result = pending ?: return true
        pending = null

        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            // User cancelled the picker: not an error, just nothing to do.
            result.success(null)
            payload = null
            return true
        }

        val outcome = runCatching {
            if (requestCode == REQUEST_EXPORT) {
                activity.contentResolver.openOutputStream(uri)?.use {
                    it.write(payload.orEmpty().toByteArray())
                }
                "ok"
            } else {
                activity.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().decodeToString() }
            }
        }
        payload = null

        outcome.fold(
            onSuccess = { result.success(it) },
            onFailure = { result.error("io", it.message, null) },
        )
        return true
    }

    /** One picker at a time; a second request while one is open is refused. */
    private fun claim(result: MethodChannel.Result): Boolean {
        if (pending != null) {
            result.error("busy", "A file picker is already open", null)
            return false
        }
        pending = result
        return true
    }
}
