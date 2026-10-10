package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.UploadRoutePaths
import com.calypsan.listenup.api.dto.uploads.UploadSessionSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.core.appJson
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.IOException
import org.w3c.files.Blob
import org.w3c.xhr.FormData
import org.w3c.xhr.XMLHttpRequest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Same budget the Ktor transport gives one file: a multi-GiB file over a slow LAN gets an hour. */
private const val FILE_TRANSFER_TIMEOUT_MS = 60 * 60 * 1_000

/**
 * Sends one picked browser file into an upload session with the browser's own XMLHttpRequest.
 *
 * Why not Ktor: its JS engine does not stream a request body. It drains the whole body into a
 * `ByteArray` and copies that into a `Uint8Array` before calling `fetch`, so an audiobook of a few
 * hundred megabytes dies inside the engine with `RangeError: Invalid array length` — after the
 * progress bar has already reached 100% counting the in-memory copy. Here the picked [Blob] goes
 * into a `FormData` as-is, the browser reads it from disk as the request drains, and
 * `upload.onprogress` reports bytes that actually left.
 *
 * The request is the one the Ktor path sends: same endpoint, `relPath` on the query string, the
 * file as the form's only part with its filename on the part (the server takes the first file
 * part, whatever it is called), the same bearer credential and client-version headers. A 401 gets
 * the single refresh-and-retry the Ktor bearer plugin gives every other request, so an upload that
 * outlives a short-lived access token does not end the session. Every failure is typed the way the
 * Ktor path types it: a status through [ErrorMapper.forHttpStatus], a dropped connection as an
 * [IOException], a timeout as Ktor's own timeout exception — both folded by [ErrorMapper].
 *
 * Cancelling the calling coroutine aborts the request, so the Cancel button stops the bytes.
 *
 * Public, with every collaborator a lambda, so the web module's browser specs can drive it against
 * a fake request; production builds it in the browser's Koin graph.
 */
@NonRpcTransport(
    NonRpcReason.BINARY_TRANSFER,
    justification = "Uploaded audio is multi-GiB binary sent as multipart; it cannot ride a JSON-RPC frame.",
)
class XhrUploadTransport(
    /** The server's base URL, or null when none is configured. */
    private val serverUrl: suspend () -> String?,
    /** The bearer credential the Ktor client would present, or null when there is no session. */
    private val accessToken: suspend () -> String?,
    /** Rotates the session after a 401 and answers the new access token, or null when it is dead. */
    private val refreshAccessToken: suspend () -> String?,
    /** Headers every request carries (the client-version pair), besides the credential. */
    private val clientHeaders: Map<String, String> = emptyMap(),
    /** Builds the request; a spec passes a fake. */
    private val newRequest: () -> XMLHttpRequest = { XMLHttpRequest() },
) {
    /**
     * Sends [file] into [sessionId] at [relPath], named [filename], reporting `(bytesSent,
     * totalBytes)` to [onProgress] on the caller's coroutine. Answers the session's updated totals.
     */
    suspend fun upload(
        sessionId: String,
        relPath: String,
        file: Blob,
        filename: String,
        onProgress: suspend (Long, Long?) -> Unit,
    ): AppResult<UploadSessionSummary> {
        val base = serverUrl() ?: return Failure(ServerUrlNotConfiguredException())
        val url =
            URLBuilder("${base.trimEnd('/')}${UploadRoutePaths.file(sessionId)}")
                .apply { parameters.append(UploadRoutePaths.REL_PATH_PARAM, relPath) }
                .buildString()
        val outgoing = OutgoingFile(url = url, file = file, filename = filename, onProgress = onProgress)

        return suspendRunCatching {
            val first = send(outgoing, accessToken())
            if (first.status != HttpStatusCode.Unauthorized.value) {
                first
            } else {
                // The one heal the bearer plugin gives a Ktor request: rotate, then send again.
                // A dead session answers null, and the original 401 stands.
                val rotated = refreshAccessToken()
                if (rotated == null) first else send(outgoing, rotated)
            }
        }.flatMap { reply -> reply.toResult(url) }
    }

    /** One send, with progress pumped from the browser's callback onto the caller's coroutine. */
    private suspend fun send(
        outgoing: OutgoingFile,
        token: String?,
    ): XhrReply =
        coroutineScope {
            // Conflated: a slow consumer wants the latest count, never a backlog of stale ones.
            val progress = Channel<Pair<Long, Long?>>(Channel.CONFLATED)
            launch { for ((sent, total) in progress) outgoing.onProgress(sent, total) }
            try {
                awaitReply(outgoing, token) { sent, total -> progress.trySend(sent to total) }
            } finally {
                // Lets the pump deliver the final count and end, so this scope can return.
                progress.close()
            }
        }

    private suspend fun awaitReply(
        outgoing: OutgoingFile,
        token: String?,
        onBytes: (Long, Long?) -> Unit,
    ): XhrReply =
        suspendCancellableCoroutine { continuation ->
            val request = newRequest()
            request.open("POST", outgoing.url)
            if (token != null) request.setRequestHeader(HttpHeaders.Authorization, "Bearer $token")
            clientHeaders.forEach { (name, value) -> request.setRequestHeader(name, value) }
            // No Content-Type: the browser writes the multipart one, boundary included.
            request.timeout = FILE_TRANSFER_TIMEOUT_MS
            request.upload.onprogress = { event ->
                onBytes(event.loaded.toLong(), if (event.lengthComputable) event.total.toLong() else null)
            }
            request.onload = {
                continuation.resume(XhrReply(status = request.status.toInt(), body = request.responseText))
            }
            request.onerror = {
                continuation.resumeWithException(IOException("The connection dropped sending ${outgoing.filename}"))
            }
            request.ontimeout = {
                continuation.resumeWithException(
                    HttpRequestTimeoutException(outgoing.url, FILE_TRANSFER_TIMEOUT_MS.toLong()),
                )
            }
            continuation.invokeOnCancellation { request.abort() }

            val form = FormData()
            form.append("file", outgoing.file, outgoing.filename)
            request.send(form)
        }

    private suspend fun XhrReply.toResult(url: String): AppResult<UploadSessionSummary> =
        if (status in SUCCESS_STATUSES) {
            suspendRunCatching { appJson.decodeFromString(UploadSessionSummary.serializer(), body) }
        } else {
            AppResult.Failure(ErrorMapper.forHttpStatus(status, debugInfo = "POST $url answered $status"))
        }

    private class OutgoingFile(
        val url: String,
        val file: Blob,
        val filename: String,
        val onProgress: suspend (Long, Long?) -> Unit,
    )

    private class XhrReply(
        val status: Int,
        val body: String,
    )

    private companion object {
        val SUCCESS_STATUSES = 200..299
    }
}
