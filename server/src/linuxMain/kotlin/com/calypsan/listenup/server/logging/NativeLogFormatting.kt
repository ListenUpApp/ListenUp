package com.calypsan.listenup.server.logging

import io.github.oshai.kotlinlogging.DefaultMessageFormatter
import io.github.oshai.kotlinlogging.FormattingAppender
import io.github.oshai.kotlinlogging.Formatter
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import platform.posix.fprintf
import platform.posix.stderr

/** Lines held while stdout is slow; past this, lines are dropped rather than blocking the server. */
private const val LOG_QUEUE_CAPACITY = 4_096

/**
 * kotlin-logging [Formatter] for the native build that renders the logger name as its final dotted
 * segment (the simple class name), mirroring the JVM `PlainFormatter`. The stored name stays
 * fully-qualified; only the displayed tag is shortened, e.g.
 * `com.calypsan.listenup.server.scanner.ScanOrchestrator` -> `[ScanOrchestrator]`.
 */
private object ShortLoggerNameFormatter : Formatter {
    private val delegate = DefaultMessageFormatter(includePrefix = true)

    override fun formatMessage(loggingEvent: KLoggingEvent): String =
        delegate.formatMessage(
            loggingEvent.copy(loggerName = loggingEvent.loggerName.substringAfterLast('.')),
        )
}

/** One formatted line bound for stdout, or stderr when [isError]. */
private class ConsoleLine(
    val text: String,
    val isError: Boolean,
)

/**
 * Formats on the logging thread, then hands the line to [sink] instead of writing it. kotlin-logging's
 * default console appender writes inline, so a stdout nobody drains blocks every thread that logs.
 */
private class QueuedConsoleAppender(
    private val sink: NonBlockingLogSink<ConsoleLine>,
) : FormattingAppender() {
    override fun logFormattedMessage(
        loggingEvent: KLoggingEvent,
        formattedMessage: Any?,
    ) {
        sink.offer(ConsoleLine(formattedMessage.toString(), isError = loggingEvent.level == Level.ERROR))
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeToConsole(line: ConsoleLine) {
    if (line.isError) fprintf(stderr, "%s\n", line.text) else println(line.text)
}

/**
 * Installs the native log formatting and routes every log line through a [NonBlockingLogSink] drained
 * on a thread of its own. Call once at process startup, before any logging; flush the returned sink
 * at shutdown.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal fun installNativeLogging(): NonBlockingLogSink<*> {
    val sink =
        NonBlockingLogSink(
            capacity = LOG_QUEUE_CAPACITY,
            scope = CoroutineScope(newSingleThreadContext("log-writer")),
            droppedNotice = { count ->
                ConsoleLine(
                    "WARN: [Logging] $count log lines dropped: the log output was not keeping up",
                    isError = false,
                )
            },
            write = ::writeToConsole,
        )
    KotlinLoggingConfiguration.direct.formatter = ShortLoggerNameFormatter
    KotlinLoggingConfiguration.direct.appender = QueuedConsoleAppender(sink)
    return sink
}
