package com.calypsan.listenup.client.core.logging

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Static bridge between the platform logging taps and the DI-owned [FileLogSink].
 *
 * The taps (Android's SLF4J tee provider, the desktop logback appender, the iOS tee logger
 * factory wrapped around kotlin-logging's OSLog backend, and the Swift `Log` bridge) are
 * installed before Koin starts, so they cannot receive the sink by injection. Instead they hand
 * every formatted line to [append]; lines observed before [attach] are held in a small
 * drop-oldest buffer and replayed once the sink exists, so early startup logging survives into
 * the file.
 */
object LogSinkRegistry {
    // Enough to cover DI startup chatter without holding a session's worth of lines.
    private const val PRE_ATTACH_CAPACITY = 256

    // Short enough that a crash waits barely longer than the write itself takes.
    private val FLUSH_POLL_INTERVAL = 5.milliseconds

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
     *
     * Drain-publish-drain: the buffer is drained BEFORE the sink reference is published so
     * concurrent [append]s cannot jump ahead of the replayed history, and drained once more
     * afterwards to catch lines that raced into the buffer during publication.
     */
    fun attach(sink: FileLogSink) {
        drainBufferInto(sink)
        sinkRef.value = sink
        drainBufferInto(sink)
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

    /**
     * Blocks the calling thread until every line appended so far is on disk, or [timeout] passes.
     * Returns true when the lines are durable, false on timeout or when no sink is attached.
     *
     * For the crash path only: an uncaught exception is about to end the process, nothing will
     * ever call [FileLogSink.close], and the line saying why must not die in the queue. Common code
     * has no blocking sleep, so the platform supplies one as [pause] (`Thread.sleep`, `usleep`).
     */
    fun flushBlocking(
        timeout: Duration,
        pause: (Duration) -> Unit,
    ): Boolean {
        val sink = sinkRef.value ?: return false
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (sink.hasPendingLines) {
            if (deadline.hasPassedNow()) return false
            pause(FLUSH_POLL_INTERVAL)
        }
        return true
    }

    /** Detaches any sink and empties the pre-attach buffer. Test isolation only. */
    internal fun resetForTests() {
        sinkRef.value = null
        while (preAttachBuffer.tryReceive().getOrNull() != null) {
            // Draining until empty is the whole job.
        }
    }

    private fun drainBufferInto(sink: FileLogSink) {
        while (true) {
            val buffered = preAttachBuffer.tryReceive().getOrNull() ?: break
            sink.submit(buffered)
        }
    }
}
