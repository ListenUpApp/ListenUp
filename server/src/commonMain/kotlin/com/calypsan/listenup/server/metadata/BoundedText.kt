package com.calypsan.listenup.server.metadata

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
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
 * Reads [response]'s body as UTF-8 text, or returns null when it is larger than [maxBytes].
 *
 * A declared length over the ceiling is refused before any byte is read; a body with no declared
 * length is streamed and abandoned once it passes the ceiling. Callers treat null the way they
 * treat an undecodable body.
 */
internal suspend fun bodyAsTextBounded(
    response: HttpResponse,
    maxBytes: Long = MAX_CATALOGUE_BODY_BYTES,
): String? {
    val declared = response.contentLength()
    if (declared != null && declared > maxBytes) return null
    val channel = response.bodyAsChannel()
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

/**
 * Issues a GET with [client] whose body has not been read when [handle] runs, so [bodyAsTextBounded] inside it
 * caps the download itself.
 *
 * A plain `get()` runs Ktor's body-saving step, which pulls the whole response into memory before
 * the caller resumes — a ceiling checked after that bounds nothing. The response is only valid
 * inside [handle] and must not escape it; the connection is released when [handle] returns.
 */
internal suspend fun <T> streamingGet(
    client: HttpClient,
    url: String,
    build: HttpRequestBuilder.() -> Unit = {},
    handle: suspend (HttpResponse) -> T,
): T = client.prepareGet(url, build).execute(handle)

/** The POST twin of [streamingGet]: the same unread body, the same scope rule for the response. */
internal suspend fun <T> streamingPost(
    client: HttpClient,
    url: String,
    build: HttpRequestBuilder.() -> Unit = {},
    handle: suspend (HttpResponse) -> T,
): T = client.preparePost(url, build).execute(handle)

private const val READ_CHUNK_BYTES = 8 * 1024
