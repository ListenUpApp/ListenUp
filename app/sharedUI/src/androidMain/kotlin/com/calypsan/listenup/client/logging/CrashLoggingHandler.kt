package com.calypsan.listenup.client.logging

import com.calypsan.listenup.client.core.logging.LogSinkRegistry
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

// Long enough for the writer to land a burst; short enough that a crash never feels like a hang.
private val CRASH_FLUSH_TIMEOUT = 1.seconds

/**
 * The process-wide uncaught-exception handler: leaves the crash in the on-device log, then lets
 * whatever was installed before it (the platform's — which shows the dialog and kills the process —
 * or a crash reporter's) do exactly what it did before.
 *
 * Logcat alone loses a crash the moment the device reboots or the buffer wraps, and the log file is
 * what Settings → Share logs hands over. So the throwable is logged at ERROR through the normal tee
 * (Logcat AND the file), the file is flushed — the process is about to end and nothing will ever
 * close the sink — and only then is the crash passed on.
 *
 * A failing flush must not swallow the crash: delegation happens regardless.
 */
internal class CrashLoggingHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val flush: () -> Unit,
    private val logger: KLogger = KotlinLogging.logger {},
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        try {
            logger.error(throwable) { "Uncaught exception on thread '${thread.name}' — the app is about to stop" }
            flush()
        } catch (_: Throwable) {
            // Recording the crash is best-effort; passing it on is not. Nothing here may stop that.
        }
        previous?.uncaughtException(thread, throwable)
    }
}

/**
 * Installs [CrashLoggingHandler] as the default uncaught-exception handler, wrapping the one already
 * there. Call once, after the file sink is attached, so the crash line has somewhere to go.
 */
internal fun installCrashLogging() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler(CrashLoggingHandler(previous = previous, flush = ::flushLogFile))
}

private fun flushLogFile() {
    LogSinkRegistry.flushBlocking(CRASH_FLUSH_TIMEOUT) { pause -> Thread.sleep(pause.inWholeMilliseconds) }
}
