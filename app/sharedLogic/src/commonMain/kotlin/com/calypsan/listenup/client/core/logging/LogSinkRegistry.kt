package com.calypsan.listenup.client.core.logging

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlin.time.Clock

/**
 * Static bridge between the platform logging taps and the DI-owned [FileLogSink].
 *
 * The taps (Android's SLF4J tee provider, the desktop logback appender) are instantiated by
 * their logging frameworks before Koin starts, so they cannot receive the sink by injection.
 * Instead they hand every formatted line to [append]; lines observed before [attach] are held
 * in a small drop-oldest buffer and replayed once the sink exists, so early startup logging
 * survives into the file.
 *
 * This registry is also the deliberate iOS seam: kotlin-logging's Darwin backend
 * (`DarwinLoggerFactory` → OSLog) exposes no appender hook, so there is no iOS tap today.
 * A future Darwin tap only needs to call [append] — nothing else changes.
 */
object LogSinkRegistry {
    // Enough to cover DI startup chatter without holding a session's worth of lines.
    private const val PRE_ATTACH_CAPACITY = 256

    // A channel doubles as a thread-safe bounded drop-oldest buffer for pre-attach lines.
    private val preAttachBuffer =
        Channel<String>(
            capacity = PRE_ATTACH_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val sinkRef = atomic<FileLogSink?>(null)

    /**
     * Hands one formatted line to the attached sink, or buffers it until a sink attaches.
     * Non-blocking; safe from any thread.
     */
    fun append(line: String) {
        val sink = sinkRef.value
        if (sink != null) {
            sink.submit(line)
        } else {
            preAttachBuffer.trySend(line)
        }
    }

    /**
     * Attaches the app's [FileLogSink], replays buffered pre-attach lines into it (they are
     * older, so they go first), then writes the `started` lifecycle marker.
     */
    fun attach(sink: FileLogSink) {
        sinkRef.value = sink
        while (true) {
            val buffered = preAttachBuffer.tryReceive().getOrNull() ?: break
            sink.submit(buffered)
        }
        sink.submit(
            formatLogLine(
                epochMillis = Clock.System.now().toEpochMilliseconds(),
                level = "INFO",
                thread = null,
                loggerName = FileLogSink.LOGGER_NAME,
                message = "app log sink started",
            ),
        )
    }
}
