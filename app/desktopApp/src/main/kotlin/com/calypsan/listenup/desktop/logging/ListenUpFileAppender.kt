package com.calypsan.listenup.desktop.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import com.calypsan.listenup.client.core.logging.LogSinkRegistry
import com.calypsan.listenup.client.core.logging.formatLogLine

/**
 * Logback appender that TAPS the existing desktop logging pipeline: the console appender
 * in `logback.xml` is untouched, while every event is re-rendered through the shared
 * [formatLogLine] and mirrored into the on-device
 * [com.calypsan.listenup.client.core.logging.FileLogSink] via [LogSinkRegistry].
 *
 * Referenced from `logback.xml`, so logback instantiates it before Koin starts — the
 * registry's pre-attach buffer keeps startup lines until `main` attaches the sink.
 */
class ListenUpFileAppender : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        val proxy = event.throwableProxy
        val throwable = (proxy as? ThrowableProxy)?.throwable
        // A non-materializable proxy (foreign serialized events) still gets its stack rendered.
        val message =
            if (throwable == null && proxy != null) {
                event.formattedMessage + "\n" + ThrowableProxyUtil.asString(proxy).trimEnd()
            } else {
                event.formattedMessage
            }
        LogSinkRegistry.append(
            formatLogLine(
                epochMillis = event.timeStamp,
                level = event.level.toString(),
                thread = event.threadName,
                loggerName = event.loggerName,
                message = message,
                throwable = throwable,
            ),
        )
    }
}
