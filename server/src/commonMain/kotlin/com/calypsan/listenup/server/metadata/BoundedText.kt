package com.calypsan.listenup.server.metadata

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * The ceiling for a third-party JSON or HTML response body. Catalogue answers are a few hundred
 * kilobytes at most; this leaves an order of magnitude of headroom.
 */
internal const val MAX_CATALOGUE_BODY_BYTES: Long = 4L * 1024 * 1024

/**
 * Reads this response's body as UTF-8 text, or returns null when it is larger than [maxBytes].
 *
 * A declared length over the ceiling is refused before any byte is read; a body with no declared
 * length is streamed and abandoned once it passes the ceiling. Callers treat null the way they
 * treat an undecodable body.
 */
internal suspend fun HttpResponse.bodyAsTextBounded(maxBytes: Long = MAX_CATALOGUE_BODY_BYTES): String? {
    val declared = contentLength()
    if (declared != null && declared > maxBytes) return null
    val channel = bodyAsChannel()
    val buffer = Buffer()
    val chunk = ByteArray(READ_CHUNK_BYTES)
    while (true) {
        val read = channel.readAvailable(chunk, 0, chunk.size)
        if (read == -1) break
        buffer.write(chunk, 0, read)
        if (buffer.size > maxBytes) return null
    }
    return buffer.readByteArray().decodeToString()
}

private const val READ_CHUNK_BYTES = 8 * 1024
