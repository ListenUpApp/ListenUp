package com.calypsan.listenup.client.core.logging

import com.calypsan.listenup.client.data.local.images.StoragePaths
import kotlinx.io.files.Path
import org.koin.mp.KoinPlatform
import platform.Foundation.NSThread
import kotlin.time.Clock

/**
 * Writes one line from the Swift `Log` facade into the shared on-device log file, beside
 * everything the Kotlin side logs.
 *
 * The caller has already decided what may be written — Swift redacts signed URLs and invite codes
 * before logging — so this only formats: [level] (`INFO`, `ERROR`…), [category] as the logger name,
 * and [message] verbatim. Safe from any thread; before the file sink exists the line waits in
 * [LogSinkRegistry]'s startup buffer.
 */
fun appendNativeLogLine(
    level: String,
    category: String,
    message: String,
) {
    LogSinkRegistry.append(
        formatLogLine(
            epochMillis = Clock.System.now().toEpochMilliseconds(),
            level = level,
            thread = if (NSThread.isMainThread) MAIN_THREAD else null,
            loggerName = category,
            message = message,
        ),
    )
}

/**
 * Absolute paths of the log files worth sharing, oldest first — what Settings → Share logs hands to
 * the share sheet. Empty until something has been written. Requires Koin to have started.
 */
fun shareableLogFilePaths(): List<String> {
    val filesDir = KoinPlatform.getKoin().get<StoragePaths>().filesDir
    return FileLogSink.existingLogFiles(Path(filesDir, FileLogSink.DIRECTORY_NAME)).map { it.toString() }
}
