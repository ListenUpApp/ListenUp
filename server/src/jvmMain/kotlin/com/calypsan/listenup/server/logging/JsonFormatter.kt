package com.calypsan.listenup.server.logging

import org.slf4j.MDC
import org.slf4j.event.Level
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** Unicode code point of the first non-control ASCII character (space). */
private const val FIRST_PRINTABLE_ASCII = 0x20

/**
 * Formats a log event as a single-line JSON object.
 *
 * Fixed fields: `timestamp`, `level`, `logger`, `thread`, `message`.
 * MDC entries are spread into the root object.
 * `stackTrace` is included as a string if a throwable is present.
 *
 * Strings are properly escaped (backslash, quote, control characters).
 */
internal fun formatJson(
    level: Level,
    loggerName: String,
    message: String,
    throwable: Throwable?,
): String {
    val timestamp = ISO_FORMATTER.format(Instant.now())
    val thread = Thread.currentThread().name
    val mdc: Map<String, String> = MDC.getCopyOfContextMap() ?: emptyMap()

    val sb = StringBuilder()
    sb.append('{')
    appendJsonField(sb, "timestamp", timestamp, first = true)
    appendJsonField(sb, "level", level.name)
    appendJsonField(sb, "logger", loggerName)
    appendJsonField(sb, "thread", thread)
    appendJsonField(sb, "message", message)

    for ((key, value) in mdc) {
        appendJsonField(sb, key, value)
    }

    if (throwable != null) {
        appendJsonField(sb, "stackTrace", formatStackTrace(throwable))
    }

    sb.append('}')
    return sb.toString()
}

private fun appendJsonField(
    sb: StringBuilder,
    key: String,
    value: String,
    first: Boolean = false,
) {
    if (!first) sb.append(',')
    sb.append('"')
    appendEscaped(sb, key)
    sb.append('"')
    sb.append(':')
    sb.append('"')
    appendEscaped(sb, value)
    sb.append('"')
}

/**
 * Appends [s] to [sb] with JSON string escaping:
 * backslash, double-quote, and control characters (U+0000–U+001F).
 */
private fun appendEscaped(
    sb: StringBuilder,
    s: String,
) {
    for (c in s) {
        when (c) {
            '\\' -> {
                sb.append("\\\\")
            }

            '"' -> {
                sb.append("\\\"")
            }

            '\n' -> {
                sb.append("\\n")
            }

            '\r' -> {
                sb.append("\\r")
            }

            '\t' -> {
                sb.append("\\t")
            }

            else -> {
                if (c.code < FIRST_PRINTABLE_ASCII) {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
    }
}

private fun formatStackTrace(t: Throwable): String {
    val sb = StringBuilder()
    appendThrowableText(sb, t)
    return sb.toString()
}

private fun appendThrowableText(
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
        appendThrowableText(sb, cause)
    }
}
