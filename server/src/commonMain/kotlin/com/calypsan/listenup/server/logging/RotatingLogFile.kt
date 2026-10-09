package com.calypsan.listenup.server.logging

import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

/** A live log file passes this before it rotates: 10 MB. */
internal const val DEFAULT_LOG_FILE_BYTES: Long = 10L * 1024 * 1024

/** Rotated files kept beside the live one: four, so the logs never pass five files' worth. */
internal const val DEFAULT_KEPT_LOG_FILES: Int = 4

/**
 * The server's log file, `server.log` in [directory]: lines are appended, and once a line would take the file past
 * [maxBytes] it rotates first — `server.log` becomes `server.log.1`, each older file moves up one, and only
 * [keptFiles] of them are kept. So the newest lines are always there and the total never passes
 * `maxBytes × (keptFiles + 1)`.
 *
 * Not thread-safe: one writer drains it (the file's [NonBlockingLogSink]). A failed write closes the file so the next
 * line reopens it; it never throws, because nothing can log that logging failed.
 */
internal class RotatingLogFile(
    private val directory: Path,
    private val maxBytes: Long = DEFAULT_LOG_FILE_BYTES,
    private val keptFiles: Int = DEFAULT_KEPT_LOG_FILES,
) {
    private val live = Path(directory, LIVE_NAME)
    private var sink: Sink? = null
    private var size = 0L

    /** Appends [line] and a newline, rotating first when it would not fit. */
    fun append(line: String) {
        val bytes = line.encodeToByteArray().size + 1L
        runCatching {
            val out = sink ?: open()
            if (size > 0 && size + bytes > maxBytes) {
                out.close()
                rotate()
                open()
            } else {
                out
            }
        }.mapCatching { out ->
            out.writeString(line)
            out.writeString("\n")
            out.flush()
            size += bytes
        }.onFailure { close() }
    }

    /** Flushes and closes the live file; a later [append] reopens it. */
    fun close() {
        runCatching { sink?.close() }
        sink = null
    }

    private fun open(): Sink {
        SystemFileSystem.createDirectories(directory)
        size = SystemFileSystem.metadataOrNull(live)?.size ?: 0L
        return SystemFileSystem.sink(live, append = true).buffered().also { sink = it }
    }

    private fun rotate() {
        sink = null
        SystemFileSystem.delete(rotated(keptFiles), mustExist = false)
        for (index in keptFiles - 1 downTo 1) {
            val older = rotated(index)
            if (SystemFileSystem.exists(older)) SystemFileSystem.atomicMove(older, rotated(index + 1))
        }
        SystemFileSystem.atomicMove(live, rotated(1))
        size = 0L
    }

    private fun rotated(index: Int) = Path(directory, "$LIVE_NAME.$index")

    private companion object {
        const val LIVE_NAME = "server.log"
    }
}
