package com.calypsan.listenup.web.logging

import com.calypsan.listenup.client.core.logging.formatLogLine
import com.calypsan.listenup.web.saveToDisk
import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.DefaultMessageFormatter
import io.github.oshai.kotlinlogging.Formatter
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.browser.window
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import org.w3c.dom.ErrorEvent
import kotlin.time.Clock
import kotlin.time.Instant

private val logger = KotlinLogging.logger("com.calypsan.listenup.web.logging.RecentLogCapture")

// Short enough that a reload a moment after an error still finds it stored; `pagehide` covers the rest.
private const val FLUSH_DELAY_MS = 1_000

/**
 * kotlin-logging's console formatter, plus the stack trace it otherwise drops — the default prints
 * only the chain of cause messages, which names a failure without saying where it happened.
 */
internal object StackTraceFormatter : Formatter {
    private val consoleFormat = DefaultMessageFormatter(includePrefix = true)

    override fun formatMessage(loggingEvent: KLoggingEvent): String {
        val formatted = consoleFormat.formatMessage(loggingEvent)
        val cause = loggingEvent.cause ?: return formatted
        return "$formatted\n${cause.stackTraceToString().trimEnd()}"
    }
}

/**
 * The browser's logging tap: every event still goes to [console] exactly as before, and is also
 * kept in [buffer] as one file-shaped line (the same [formatLogLine] Android and iOS write), stack
 * trace included.
 */
internal class RecentLogAppender(
    private val console: Appender,
    private val buffer: RecentLogBuffer,
) : Appender {
    override fun log(loggingEvent: KLoggingEvent) {
        console.log(loggingEvent)
        val message = loggingEvent.message.orEmpty()
        buffer.append(
            formatLogLine(
                epochMillis = loggingEvent.timestamp,
                level = loggingEvent.level.name,
                thread = null,
                loggerName = loggingEvent.loggerName,
                message = loggingEvent.marker?.let { marker -> "${marker.getName()} $message" } ?: message,
                throwable = loggingEvent.cause,
            ),
        )
    }
}

/**
 * Starts keeping the browser's recent log: installs [RecentLogAppender] in front of whatever
 * console appender is configured, switches the console to [StackTraceFormatter], flushes on
 * `pagehide`, and logs what would otherwise only reach the console as an uncaught error.
 *
 * Call once, before anything else logs — lines logged earlier reach the console but not the file.
 */
internal fun installRecentLogCapture(): RecentLogBuffer {
    val buffer =
        RecentLogBuffer(
            store = LocalStorageLogStore(),
            scheduleFlush = { task -> window.setTimeout({ task() }, FLUSH_DELAY_MS) },
        )
    val direct = KotlinLoggingConfiguration.direct
    direct.formatter = StackTraceFormatter
    direct.appender = RecentLogAppender(console = direct.appender, buffer = buffer)
    // A reload or tab close inside the debounce window would otherwise lose the newest lines —
    // usually the ones that explain why the reader reloaded.
    window.addEventListener("pagehide", { buffer.flush() })
    logUncaughtErrors()
    return buffer
}

/**
 * Sends uncaught errors and unhandled promise rejections through the logger at ERROR, so they land
 * in the recent log rather than only in a console nobody had open.
 */
private fun logUncaughtErrors() {
    window.addEventListener("error", { event ->
        // Resource load failures (a broken <img>) arrive as plain Events, not ErrorEvents.
        val error = event as? ErrorEvent ?: return@addEventListener
        logger.error(error.error as? Throwable) {
            "Uncaught error: ${error.message} (${error.filename}:${error.lineno}:${error.colno})"
        }
    })
    window.addEventListener("unhandledrejection", { event ->
        val reason: dynamic = event.asDynamic().reason
        logger.error(reason as? Throwable) { "Unhandled promise rejection: $reason" }
    })
}

/** Saves [buffer]'s lines as a text file named for [now] — Settings → Download logs. */
internal fun downloadRecentLogs(
    buffer: RecentLogBuffer,
    now: Instant = Clock.System.now(),
) {
    saveToDisk(
        filename = recentLogsFilename(now, TimeZone.currentSystemDefault()),
        bytes = buffer.text().encodeToByteArray(),
        type = "text/plain",
    )
}

private val filenameTimestamp =
    LocalDateTime.Format {
        year()
        char('-')
        monthNumber()
        char('-')
        day()
        char('T')
        hour()
        char('-')
        minute()
        char('-')
        second()
    }

/** `listenup-logs-2026-10-09T16-32-05.log` — local time, and no colons, which some file systems refuse. */
internal fun recentLogsFilename(
    now: Instant,
    timeZone: TimeZone,
): String = "listenup-logs-${filenameTimestamp.format(now.toLocalDateTime(timeZone))}.log"
