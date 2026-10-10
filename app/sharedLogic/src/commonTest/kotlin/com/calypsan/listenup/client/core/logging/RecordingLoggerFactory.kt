package com.calypsan.listenup.client.core.logging

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEventBuilder
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import io.github.oshai.kotlinlogging.Marker

/** One log call as a [RecordingLoggerFactory] saw it. */
data class RecordedLogEvent(
    val loggerName: String,
    val level: Level,
    val message: String?,
    val cause: Throwable?,
)

/**
 * A [KLoggerFactory] that keeps every event its loggers receive, at every level, so a spec can
 * assert on what code logged without depending on whichever backend the platform runs.
 *
 * Only reaches loggers obtained while it is installed — see [withRecordingLoggerFactory].
 */
class RecordingLoggerFactory : KLoggerFactory {
    private val recorded = mutableListOf<RecordedLogEvent>()

    /** Everything logged so far, oldest first. */
    val events: List<RecordedLogEvent> get() = recorded.toList()

    override fun logger(name: String): KLogger =
        object : KLogger {
            override val name: String = name

            override fun isLoggingEnabledFor(
                level: Level,
                marker: Marker?,
            ): Boolean = level != Level.OFF

            override fun at(
                level: Level,
                marker: Marker?,
                block: KLoggingEventBuilder.() -> Unit,
            ) {
                val event = KLoggingEventBuilder().apply(block)
                recorded += RecordedLogEvent(name, level, event.message, event.cause)
            }
        }
}

/** Runs [body] with a [RecordingLoggerFactory] installed globally, restoring the previous factory after. */
inline fun <T> withRecordingLoggerFactory(body: (RecordingLoggerFactory) -> T): T {
    val previous = KotlinLoggingConfiguration.loggerFactory
    val recording = RecordingLoggerFactory()
    KotlinLoggingConfiguration.loggerFactory = recording
    try {
        return body(recording)
    } finally {
        KotlinLoggingConfiguration.loggerFactory = previous
    }
}
