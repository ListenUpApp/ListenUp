package com.calypsan.listenup.client.logging

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import uk.uuid.slf4j.android.ServiceProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * SLF4J 2.x service provider that TAPS the existing Android logging pipeline rather than
 * replacing it: every logger it hands out is a [TeeLogger] wrapping the real slf4j-android
 * logger, so Logcat output is unchanged while each line is mirrored into the on-device
 * [com.calypsan.listenup.client.core.logging.FileLogSink].
 *
 * Selected explicitly via the `slf4j.provider` system property (set in the `ListenUp`
 * application class's `init` block, before any SLF4J binding can happen) instead of
 * `META-INF/services` discovery — both providers live on the classpath, and the property
 * makes the choice deterministic without a "multiple providers" warning.
 */
class ListenUpAndroidLogProvider : SLF4JServiceProvider {
    private val delegate = ServiceProvider()
    private val loggers = ConcurrentHashMap<String, Logger>()

    private val teeLoggerFactory =
        ILoggerFactory { name ->
            loggers.getOrPut(name) { TeeLogger(name, delegate.loggerFactory.getLogger(name)) }
        }

    override fun getLoggerFactory(): ILoggerFactory = teeLoggerFactory

    override fun getMarkerFactory(): IMarkerFactory = delegate.markerFactory

    override fun getMDCAdapter(): MDCAdapter = delegate.mdcAdapter

    override fun getRequestedApiVersion(): String = ServiceProvider.REQUESTED_API_VERSION

    override fun initialize() = delegate.initialize()
}
