package com.calypsan.listenup.client.features.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.calypsan.listenup.client.core.logging.FileLogSink
import java.io.File

/**
 * Android implementation of [SettingsPlatformActions]: exposes the [FileLogSink] files
 * through the app's FileProvider and hands them to the system share sheet.
 */
class AndroidSettingsPlatformActions(
    private val context: Context,
) : SettingsPlatformActions {
    override fun shareLogs() {
        val logsDir = File(context.filesDir, FileLogSink.DIRECTORY_NAME)
        // Oldest first so a receiver concatenating attachments reads chronologically.
        val logFiles =
            listOf(FileLogSink.ROTATED_FILE_NAME, FileLogSink.FILE_NAME)
                .map { File(logsDir, it) }
                .filter { it.exists() && it.length() > 0 }
        if (logFiles.isEmpty()) return

        val authority = "${context.packageName}.fileprovider"
        val uris = logFiles.map { FileProvider.getUriForFile(context, authority, it) }

        val sendIntent =
            if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.single()) }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }.apply {
                type = "text/plain"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        val chooser =
            Intent.createChooser(sendIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(chooser)
    }
}
