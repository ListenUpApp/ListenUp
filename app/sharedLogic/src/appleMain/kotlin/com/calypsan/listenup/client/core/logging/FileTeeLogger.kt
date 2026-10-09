package com.calypsan.listenup.client.core.logging

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEventBuilder
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import io.github.oshai.kotlinlogging.Marker
import platform.Foundation.NSThread
import kotlin.time.Clock

/**
 * The iOS logging tap: a [KLogger] that hands every event, unchanged, to the platform [delegate]
 * (kotlin-logging's OSLog-backed Darwin logger) and also writes it to the shared log file through
 * [LogSinkRegistry] — formatted by [formatLogLine], stack trace included, so the file reads exactly
 * like Android's.
 *
 * ⛔ Implements [KLogger] directly rather than `by delegate`. The level methods (`info {}`,
 * `error(t) {}`) are interface defaults that funnel into [at] only when they are NOT delegated; with
 * `by`, they would go straight to OSLog and nothing would reach the file.
 *
 * Level gating is the delegate's: a level OSLog has disabled is equally absent from the file, the
 * same rule Android's `TeeLogger` follows.
 */
internal class FileTeeLogger(
    private val delegate: KLogger,
) : KLogger {
    override val name: String get() = delegate.name

    override fun isLoggingEnabledFor(
        level: Level,
        marker: Marker?,
    ): Boolean = delegate.isLoggingEnabledFor(level, marker)

    override fun at(
        level: Level,
        marker: Marker?,
        block: KLoggingEventBuilder.() -> Unit,
    ) {
        if (!isLoggingEnabledFor(level, marker)) return
        // Built once: the caller's block may be expensive, and both halves must see the same event.
        val event = KLoggingEventBuilder().apply(block)
        delegate.at(level, marker) {
            message = event.message
            cause = event.cause
            payload = event.payload
        }
        val message = event.message.orEmpty()
        LogSinkRegistry.append(
            formatLogLine(
                epochMillis = Clock.System.now().toEpochMilliseconds(),
                level = level.name,
                thread = if (NSThread.isMainThread) MAIN_THREAD else null,
                loggerName = name,
                message = marker?.let { "${it.getName()} $message" } ?: message,
                throwable = event.cause,
            ),
        )
    }
}

/** Hands out [FileTeeLogger]s wrapped around the loggers [delegate] creates. */
internal class FileTeeLoggerFactory(
    private val delegate: KLoggerFactory,
) : KLoggerFactory {
    override fun logger(name: String): KLogger = FileTeeLogger(delegate.logger(name))
}

/**
 * Wraps kotlin-logging's active factory (on iOS, `DarwinLoggerFactory`) in a [FileTeeLoggerFactory].
 * Idempotent.
 *
 * Call before anything obtains a logger: a logger created earlier was made by the unwrapped factory
 * and reaches OSLog only. Kotlin/Native initialises top-level properties on first access, so every
 * file-level `KotlinLogging.logger {}` touched after this call is teed.
 */
internal fun installFileLogTap() {
    val active = KotlinLoggingConfiguration.loggerFactory
    if (active !is FileTeeLoggerFactory) {
        KotlinLoggingConfiguration.loggerFactory = FileTeeLoggerFactory(active)
    }
}

internal const val MAIN_THREAD = "main"
