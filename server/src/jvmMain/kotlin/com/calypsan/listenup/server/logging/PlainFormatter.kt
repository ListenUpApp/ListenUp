package com.calypsan.listenup.server.logging

import org.slf4j.MDC
import org.slf4j.event.Level
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/**
 * Formats a log event as human-readable plain text.
 *
 * Pattern: `yyyy-MM-dd HH:mm:ss.SSS [thread] LEVEL [correlationId] logger - message`
 * Followed by `\tat class.method(file:line)` lines if a throwable is present.
 *
 * Logger name is rendered as its final dotted segment (the simple class name), e.g.
 * `com.calypsan.listenup.server.scanner.ScanOrchestrator` -> `ScanOrchestrator`.
 */
internal fun formatPlain(
    level: Level,
    loggerName: String,
    message: String,
    throwable: Throwable?,
): String {
    val timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER)
    val thread = Thread.currentThread().name
    val correlationId = MDC.get("correlationId") ?: ""
    val shortName = loggerName.substringAfterLast('.')

    val sb = StringBuilder()
    sb.append(timestamp)
    sb.append(" [").append(thread).append("] ")
    sb.append(level.name.padEnd(5))
    sb.append(" [").append(correlationId).append("] ")
    sb.append(shortName)
    sb.append(" - ")
    sb.append(message)

    if (throwable != null) {
        sb.append('\n')
        appendThrowable(sb, throwable)
    }

    return sb.toString()
}

private fun appendThrowable(
    sb: StringBuilder,
    t: Throwable,
) {
    sb.append(t::class.java.name)
    if (t.message != null) {
        sb.append(": ").append(t.message)
    }
    for (element in t.stackTrace) {
        sb.append("\n\tat ").append(element)
    }
    val cause = t.cause
    if (cause != null) {
        sb.append("\nCaused by: ")
        appendThrowable(sb, cause)
    }
}
