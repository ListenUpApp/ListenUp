package com.calypsan.listenup.client.features.settings

import com.calypsan.listenup.client.core.logging.FileLogSink
import com.calypsan.listenup.client.data.local.images.StoragePaths
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.IOException

/**
 * Desktop implementation of [SettingsPlatformActions]: reveals the log directory in the
 * platform file manager; if the desktop cannot open folders (headless/unsupported), the
 * directory path is copied to the clipboard instead — matching the clipboard affordance
 * desktop uses for other share actions.
 */
class DesktopSettingsPlatformActions(
    private val storagePaths: StoragePaths,
) : SettingsPlatformActions {
    override fun shareLogs() {
        val logsDir = File(storagePaths.filesDir.toString(), FileLogSink.DIRECTORY_NAME)
        logsDir.mkdirs()

        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            try {
                Desktop.getDesktop().open(logsDir)
                return
            } catch (_: IOException) {
                // Fall through to the clipboard fallback below.
            }
        }
        val selection = StringSelection(logsDir.absolutePath)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
    }
}
