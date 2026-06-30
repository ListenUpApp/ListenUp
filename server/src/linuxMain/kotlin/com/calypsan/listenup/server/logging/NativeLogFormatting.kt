package com.calypsan.listenup.server.logging

import io.github.oshai.kotlinlogging.DefaultMessageFormatter
import io.github.oshai.kotlinlogging.Formatter
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration

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

/**
 * Installs [ShortLoggerNameFormatter] as the active native kotlin-logging formatter. Call once at
 * process startup, before any logging.
 */
internal fun installNativeLogFormatting() {
    KotlinLoggingConfiguration.direct.formatter = ShortLoggerNameFormatter
}
