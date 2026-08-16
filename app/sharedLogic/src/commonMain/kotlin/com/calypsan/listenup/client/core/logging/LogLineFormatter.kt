package com.calypsan.listenup.client.core.logging

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// Matches logback's `%-5level` column so file lines line up with the desktop console.
private const val LEVEL_COLUMN_WIDTH = 5

// `yyyy-MM-dd HH:mm:ss.SSS` — the timestamp shape users already know from the
// desktop logback pattern (and the shape logcat renders on Android).
private val timestampFormat =
    LocalDateTime.Format {
        year()
        char('-')
        monthNumber()
        char('-')
        day()
        char(' ')
        hour()
        char(':')
        minute()
        char(':')
        second()
        char('.')
        secondFraction(3)
    }

/**
 * Renders one on-disk log line in the shape users already see on the console:
 * `yyyy-MM-dd HH:mm:ss.SSS [thread] LEVEL logger - message`, with the throwable's
 * stack trace appended on the following lines when present.
 *
 * Pure and platform-independent so every tap point (Android SLF4J tee, desktop
 * logback appender, a future iOS appender) produces byte-identical file lines.
 */
fun formatLogLine(
    epochMillis: Long,
    level: String,
    thread: String?,
    loggerName: String,
    message: String,
    throwable: Throwable? = null,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val localTime = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone)
    return buildString {
        append(timestampFormat.format(localTime))
        append(" [")
        append(thread ?: "-")
        append("] ")
        append(level.padEnd(LEVEL_COLUMN_WIDTH))
        append(' ')
        append(loggerName)
        append(" - ")
        append(message)
        throwable?.let {
            append('\n')
            append(it.stackTraceToString().trimEnd())
        }
    }
}
