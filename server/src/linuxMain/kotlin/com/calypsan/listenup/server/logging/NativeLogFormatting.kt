package com.calypsan.listenup.server.logging

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
import kotlinx.io.files.Path
import platform.posix.fprintf
import platform.posix.stderr
import kotlin.time.Duration
import kotlin.time.Clock

/** Lines held while an output is slow; past this, lines are dropped rather than blocking the server. */
private const val LOG_QUEUE_CAPACITY = 4_096

/**
 * The native line format ([renderLogLine]): the logger's simple name, as the JVM `PlainFormatter` shows it, and a
 * failure's whole throwable. kotlin-logging's native default printed a cause's message only — no class, no frames.
 */
private object NativeLineFormatter : Formatter {
    override fun formatMessage(loggingEvent: KLoggingEvent): String =
        renderLogLine(loggingEvent.level, loggingEvent.loggerName, loggingEvent.message, loggingEvent.cause)
}

/** One formatted line bound for stdout, or stderr when [isError]. */
internal class ConsoleLine(
    val text: String,
    val isError: Boolean,
)

/**
 * Formats on the logging thread, then hands the line to each output's sink instead of writing it. kotlin-logging's
 * default console appender writes inline, so a stdout nobody drains blocks every thread that logs. The file, when
 * there is one, gets every line but [CONSOLE_ONLY_LOGGER]'s, stamped with when it was logged.
 */
private class QueuedAppender(
    private val console: NonBlockingLogSink<ConsoleLine>,
    private val file: NonBlockingLogSink<String>?,
) : FormattingAppender() {
    override fun logFormattedMessage(
        loggingEvent: KLoggingEvent,
        formattedMessage: Any?,
    ) {
        val text = formattedMessage.toString()
        console.offer(ConsoleLine(text, isError = loggingEvent.level == Level.ERROR))
        if (file != null && writesToLogFile(loggingEvent.loggerName)) {
            // The wall clock, not loggingEvent.timestamp: on native that is not epoch time (every line read 1970).
            file.offer(fileLogLine(Clock.System.now(), text))
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeToConsole(line: ConsoleLine) {
    if (line.isError) fprintf(stderr, "%s\n", line.text) else println(line.text)
}

/** The process's log outputs, flushed together at shutdown. */
internal class NativeLogOutputs(
    private val console: NonBlockingLogSink<ConsoleLine>,
    private val fileSink: NonBlockingLogSink<String>?,
    private val file: RotatingLogFile?,
) {
    /** Writes what is queued, within [timeout] each, then closes the file. False when an output did not keep up. */
    suspend fun closeAndDrain(timeout: Duration): Boolean {
        val consoleDrained = console.closeAndDrain(timeout)
        val fileDrained = fileSink?.closeAndDrain(timeout) ?: true
        file?.close()
        return consoleDrained && fileDrained
    }
}

/**
 * Installs the native log format and routes every line through [NonBlockingLogSink]s, each drained on a thread of
 * its own, so a stalled output — the NAS's stdout, a slow disk — costs lines, never a request, and never the other
 * output. With a [logDirectory], lines are also kept in a [RotatingLogFile] there, which an operator can read
 * without `docker logs`. [level] is the threshold (`LISTENUP_LOG_LEVEL`, else INFO). Call once at process startup,
 * before any logging; drain the returned outputs at shutdown.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal fun installNativeLogging(
    logDirectory: Path?,
    level: Level,
): NativeLogOutputs {
    val console =
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
    val file = logDirectory?.let { RotatingLogFile(it) }
    val fileSink =
        file?.let { log ->
            NonBlockingLogSink(
                capacity = LOG_QUEUE_CAPACITY,
                scope = CoroutineScope(newSingleThreadContext("log-file-writer")),
                droppedNotice = { count ->
                    "WARN: [Logging] $count log lines dropped: the log file was not keeping up"
                },
                write = log::append,
            )
        }
    KotlinLoggingConfiguration.direct.logLevel = level
    KotlinLoggingConfiguration.direct.formatter = NativeLineFormatter
    KotlinLoggingConfiguration.direct.appender = QueuedAppender(console, fileSink)
    return NativeLogOutputs(console, fileSink, file)
}
