package com.calypsan.listenup.server.logging

import kotlinx.coroutines.CancellationException
import org.slf4j.MDC
import org.slf4j.event.Level
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter

/**
 * SLF4J [org.slf4j.Logger] implementation that formats and writes log events to stdout.
 *
 * Level filtering delegates to [ListenUpLoggerFactory.levelFor] so that prefix-based
 * suppression (e.g. `io.netty` → WARN) is a single, tested rule in the factory.
 *
 * Each [handleNormalizedLoggingCall] call writes a single [println] to [System.out],
 * which is internally synchronized, guaranteeing atomic multi-line output under
 * concurrent access.
 */
class ListenUpLogger(
    name: String,
    private val factory: ListenUpLoggerFactory,
) : LegacyAbstractLogger() {
    init {
        this.name = name
    }

    override fun getFullyQualifiedCallerName(): String? = null

    // ----- Level predicates -------------------------------------------------

    private fun isEnabled(level: Level): Boolean = level.toInt() >= factory.levelFor(name).toInt()

    override fun isTraceEnabled(): Boolean = isEnabled(Level.TRACE)

    override fun isDebugEnabled(): Boolean = isEnabled(Level.DEBUG)

    override fun isInfoEnabled(): Boolean = isEnabled(Level.INFO)

    override fun isWarnEnabled(): Boolean = isEnabled(Level.WARN)

    override fun isErrorEnabled(): Boolean = isEnabled(Level.ERROR)

    // ----- Core dispatch ----------------------------------------------------

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: org.slf4j.Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        val formattedMessage =
            if (arguments.isNullOrEmpty()) {
                messagePattern ?: ""
            } else {
                MessageFormatter.arrayFormat(messagePattern, arguments).message
            }

        // Snapshot MDC at the moment of the call so the capture and formatter see the same values.
        val mdcSnapshot: Map<String, String> = MDC.getCopyOfContextMap() ?: emptyMap()

        val line =
            try {
                if (factory.isJsonFormat) {
                    formatJson(level, name, formattedMessage, throwable)
                } else {
                    formatPlain(level, name, formattedMessage, throwable)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Never let formatter failures silence a log or propagate into the caller.
                "[$level] $name - $formattedMessage"
            }

        // System.out.println is synchronized — a single call is atomic.
        System.out.println(line)

        // Test capture hook — non-null only in tests.
        factory.testCapture?.add(
            CapturedEvent(
                level = level,
                loggerName = name,
                message = formattedMessage,
                mdc = mdcSnapshot,
                throwable = throwable,
            ),
        )
    }
}
