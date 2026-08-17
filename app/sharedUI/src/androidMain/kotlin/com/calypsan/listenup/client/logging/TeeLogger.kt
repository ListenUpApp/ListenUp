package com.calypsan.listenup.client.logging

import com.calypsan.listenup.client.core.logging.LogSinkRegistry
import com.calypsan.listenup.client.core.logging.formatLogLine
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.MessageFormatter

/**
 * SLF4J logger that forwards every call verbatim to the wrapped slf4j-android [delegate]
 * (so Logcat output is byte-identical to before) and mirrors the formatted line into
 * [LogSinkRegistry] for on-device persistence.
 *
 * Level gating is inherited from the delegate: [AbstractLogger]'s public methods consult
 * `is*Enabled` before funnelling here, and those all delegate — a level disabled for
 * Logcat is equally absent from the file.
 */
internal class TeeLogger(
    name: String,
    private val delegate: Logger,
) : AbstractLogger() {
    init {
        this.name = name
    }

    override fun isTraceEnabled(): Boolean = delegate.isTraceEnabled

    override fun isTraceEnabled(marker: Marker?): Boolean = delegate.isTraceEnabled(marker)

    override fun isDebugEnabled(): Boolean = delegate.isDebugEnabled

    override fun isDebugEnabled(marker: Marker?): Boolean = delegate.isDebugEnabled(marker)

    override fun isInfoEnabled(): Boolean = delegate.isInfoEnabled

    override fun isInfoEnabled(marker: Marker?): Boolean = delegate.isInfoEnabled(marker)

    override fun isWarnEnabled(): Boolean = delegate.isWarnEnabled

    override fun isWarnEnabled(marker: Marker?): Boolean = delegate.isWarnEnabled(marker)

    override fun isErrorEnabled(): Boolean = delegate.isErrorEnabled

    override fun isErrorEnabled(marker: Marker?): Boolean = delegate.isErrorEnabled(marker)

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        // 1) Logcat, via the unwrapped backend: the fluent builder's default implementation
        // dispatches back onto the delegate's ordinary level methods, so slf4j-android
        // formats and tags exactly as it did before the tee existed.
        val builder = delegate.atLevel(level)
        if (marker != null) builder.addMarker(marker)
        if (throwable != null) builder.setCause(throwable)
        if (arguments == null) {
            builder.log(messagePattern)
        } else {
            builder.log(messagePattern, *arguments)
        }

        // 2) File sink: one pre-formatted line carrying only what the log call itself said.
        LogSinkRegistry.append(
            formatLogLine(
                epochMillis = System.currentTimeMillis(),
                level = level.name,
                thread = Thread.currentThread().name,
                loggerName = name,
                message = MessageFormatter.basicArrayFormat(messagePattern, arguments),
                throwable = throwable,
            ),
        )
    }
}
