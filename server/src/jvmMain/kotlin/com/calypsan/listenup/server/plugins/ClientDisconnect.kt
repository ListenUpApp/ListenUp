package com.calypsan.listenup.server.plugins

import io.ktor.util.cio.ChannelIOException
import io.ktor.utils.io.ClosedByteChannelException
import java.io.IOException

/** Substrings (case-insensitive) that mark a socket write failing because the peer closed it. */
private val CLIENT_DISCONNECT_MARKERS = listOf("broken pipe", "connection reset")

/**
 * Whether [throwable] (or anything in its cause chain) is a client closing the connection
 * mid-response — a seek/skip/pause/background during audio streaming, an SSE subscriber
 * navigating away, or any aborted download. The single source of truth shared by the audio/REST
 * error handler ([installAppErrorStatusPages]) and the SSE firehose.
 *
 * Matches Ktor's closed-channel families structurally — [ClosedByteChannelException] (covers
 * `ClosedWriteChannelException` / `ClosedReadChannelException`) and [ChannelIOException] (covers
 * `ChannelWriteException` / `ChannelReadException`) — plus an [IOException] whose message marks a
 * broken pipe / connection reset (the OS-level cause the CIO engine usually wraps). A genuine
 * server-side disk [IOException] ("Input/output error", "No space left on device") has a different
 * message and is deliberately NOT matched — it stays a real 500.
 */
internal fun isClientDisconnect(throwable: Throwable): Boolean {
    val seen = HashSet<Throwable>()
    var current: Throwable? = throwable
    while (current != null && seen.add(current)) {
        if (current is ClosedByteChannelException || current is ChannelIOException) return true
        val message = current.message
        if (current is IOException && message != null &&
            CLIENT_DISCONNECT_MARKERS.any { message.contains(it, ignoreCase = true) }
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
