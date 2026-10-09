package com.calypsan.listenup.server.logging

import io.github.oshai.kotlinlogging.Level
import kotlin.time.Instant

/**
 * The logger whose lines go to the console only, never the log file: for the rare line that must be shown once
 * and must not persist — the root-reset hatch's one-time token, which takes the root account for anyone who reads
 * it. A file outlives the token's window and is read by more people than the console.
 */
internal const val CONSOLE_ONLY_LOGGER = "com.calypsan.listenup.server.logging.ConsoleOnly"

/**
 * One log line: `LEVEL: [SimpleName] message`, and, for a failure, the whole [throwable] beneath it — class,
 * message, causes and frames. The native default printed only the message, so a crash on the production server
 * said what went wrong but not where or as what.
 */
internal fun renderLogLine(
    level: Level,
    loggerName: String,
    message: String?,
    throwable: Throwable?,
): String {
    val head = "${level.name}: [${loggerName.substringAfterLast('.')}] ${message.orEmpty()}"
    return if (throwable == null) head else "$head\n${throwable.stackTraceToString().trimEnd()}"
}

/** A line as the log file holds it: [line] after the UTC instant it was written, which the console leaves to Docker. */
internal fun fileLogLine(
    at: Instant,
    line: String,
): String = "$at $line"

/** Whether [loggerName]'s lines belong in the log file; only [CONSOLE_ONLY_LOGGER]'s do not. */
internal fun writesToLogFile(loggerName: String): Boolean = loggerName != CONSOLE_ONLY_LOGGER

/** The level `LISTENUP_LOG_LEVEL` names (any case), or null when it is unset or names none. */
internal fun parseLogLevel(raw: String?): Level? {
    if (raw == null) return null
    val name = raw.trim().uppercase()
    return Level.entries.firstOrNull { it.name == name }
}
