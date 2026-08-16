package com.calypsan.listenup.client.core.logging

import com.calypsan.listenup.core.appCoroutineExceptionHandler
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import kotlin.time.Clock

/**
 * Rotating on-device log file fed by the platform logging tap (see [LogSinkRegistry]).
 *
 * Behaviour contract:
 * - **Never blocks the caller.** [submit] enqueues onto a bounded channel; on overflow the
 *   OLDEST queued lines are dropped (a log sink must not backpressure the app) and the next
 *   written batch records a `dropped N log lines` marker.
 * - **Single writer.** One coroutine on the supplied [dispatcher] drains the queue in batches
 *   and flushes after every drained batch, so lines never interleave and the loss window on
 *   process death is at most the burst currently in flight.
 * - **Rotation.** When [FILE_NAME] would exceed [maxFileBytes] it is renamed to
 *   [ROTATED_FILE_NAME] (replacing any previous one) — exactly two files, ~2x [maxFileBytes]
 *   total on disk.
 * - **Failure policy.** On an I/O error the sink abandons persistence for the session rather
 *   than crash or spam: console/logcat logging is produced upstream and never affected.
 *
 * The sink serializes exactly the lines handed to it — it adds no metadata of its own beyond
 * the started/rotated/dropped lifecycle markers.
 */
class FileLogSink(
    private val directory: Path,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    dispatcher: CoroutineDispatcher,
) {
    private val droppedLines = atomic(0)

    // DROP_OLDEST keeps trySend infallible for producers; onUndeliveredElement fires for
    // each line the overflow policy evicts, which is how the drop marker gets its count.
    private val queue =
        Channel<String>(
            capacity = queueCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { droppedLines.incrementAndGet() },
        )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher + appCoroutineExceptionHandler)
    private val writer = scope.launch { runWriter() }

    /**
     * Enqueues one already-formatted line for persistence. Non-blocking and safe to call
     * from any thread; silently a no-op once [close] has run.
     */
    fun submit(line: String) {
        queue.trySend(line)
    }

    /**
     * Stops accepting lines, drains what is queued, flushes, and closes the file.
     * Idempotent. Intended for app-shutdown seams (e.g. the desktop window close).
     */
    suspend fun close() {
        queue.close()
        writer.join()
        scope.cancel()
    }

    private suspend fun runWriter() {
        try {
            val file = RotatingFile(directory, maxFileBytes)
            for (first in queue) {
                file.writeLine(first)
                // Drain whatever else is already queued so a burst becomes one batch.
                while (true) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    file.writeLine(next)
                }
                val dropped = droppedLines.getAndSet(0)
                if (dropped > 0) {
                    file.writeLine(lifecycleMarker("dropped $dropped log lines (queue overflow)"))
                }
                file.flush()
            }
            file.closeFile()
        } catch (_: IOException) {
            // Persistence is best-effort: on disk failure stop writing for this session.
            // Console/logcat output is produced upstream of this sink and is unaffected.
        }
    }

    /** Size-tracked append target: rotates `listenup.log` into `listenup.log.1` at the cap. */
    private inner class RotatingFile(
        directory: Path,
        private val maxFileBytes: Long,
    ) {
        private val currentPath = Path(directory, FILE_NAME)
        private val rotatedPath = Path(directory, ROTATED_FILE_NAME)
        private var bytesWritten: Long
        private var out: Sink

        init {
            SystemFileSystem.createDirectories(directory)
            bytesWritten = SystemFileSystem.metadataOrNull(currentPath)?.size ?: 0L
            out = SystemFileSystem.sink(currentPath, append = true).buffered()
        }

        fun writeLine(line: String) {
            val bytes = line.encodeToByteArray()
            if (bytesWritten > 0 && bytesWritten + bytes.size + 1 > maxFileBytes) {
                rotate()
                writeLine(lifecycleMarker("log rotated"))
            }
            out.write(bytes)
            out.writeByte(NEWLINE)
            bytesWritten += bytes.size + 1
        }

        fun flush() = out.flush()

        fun closeFile() {
            out.flush()
            out.close()
        }

        private fun rotate() {
            closeFile()
            if (SystemFileSystem.exists(rotatedPath)) SystemFileSystem.delete(rotatedPath)
            SystemFileSystem.atomicMove(currentPath, rotatedPath)
            out = SystemFileSystem.sink(currentPath).buffered()
            bytesWritten = 0
        }
    }

    private fun lifecycleMarker(message: String): String =
        formatLogLine(
            epochMillis = Clock.System.now().toEpochMilliseconds(),
            level = "INFO",
            thread = null,
            loggerName = LOGGER_NAME,
            message = message,
        )

    companion object {
        /** Subdirectory of the app's private files dir that holds the log files. */
        const val DIRECTORY_NAME: String = "logs"

        /** Active log file name. */
        const val FILE_NAME: String = "listenup.log"

        /** Rotated (older) log file name — at most one is kept. */
        const val ROTATED_FILE_NAME: String = "listenup.log.1"

        /** ~1 MB per file; two files cap total persistence at ~2 MB. */
        const val DEFAULT_MAX_FILE_BYTES: Long = 1024L * 1024L

        /** Bounded queue between log callers and the single writer coroutine. */
        const val DEFAULT_QUEUE_CAPACITY: Int = 1024

        /** Logger name stamped on the sink's own lifecycle marker lines. */
        internal const val LOGGER_NAME: String = "com.calypsan.listenup.client.core.logging.FileLogSink"

        private const val NEWLINE: Byte = '\n'.code.toByte()
    }
}
