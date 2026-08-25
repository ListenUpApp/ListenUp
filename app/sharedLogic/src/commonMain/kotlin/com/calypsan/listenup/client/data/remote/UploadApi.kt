package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.UploadRoutePaths
import com.calypsan.listenup.api.dto.uploads.UploadFinalizeResult
import com.calypsan.listenup.api.dto.uploads.UploadSessionSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.core.FileSource
import io.ktor.client.call.body
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

/** A single audio file can be multi-GiB over a slow LAN; the transfer gets an hour. */
private const val FILE_TRANSFER_TIMEOUT_MS = 60L * 60 * 1_000

/** Finalize moves every staged file into the library and kicks off ingest — minutes, not seconds. */
private const val FINALIZE_TIMEOUT_MS = 15L * 60 * 1_000

/**
 * Raw-HTTP implementation of [UploadApiContract].
 *
 * Bytes never buffer: [ChannelProvider] opens the [FileSource] on demand as the request body
 * drains, so a 4 GiB audiobook streams through a constant-size window rather than through the
 * client's heap. Progress rides Ktor's own `onUpload` hook for the same reason — counting whole
 * files would leave a single-file book sitting at 0% for its entire upload and then jumping to
 * 100%, which is exactly the kind of lie the app is supposed to not tell.
 *
 * The multipart part name is not load-bearing: the server takes the *first* file part it decodes,
 * whatever it is called. `relPath` rides the query string instead, because the server validates it
 * — and the session, and the quota — **before reading a single byte of the body**, so nothing that
 * arrives on the wire can influence where it lands.
 */
@NonRpcTransport(
    NonRpcReason.BINARY_TRANSFER,
    justification = "Uploaded audio is multi-GiB binary streamed as multipart; it cannot ride a JSON-RPC frame.",
)
internal class UploadApi(
    private val clientFactory: ApiClientFactory,
) : UploadApiContract {
    override suspend fun createSession(): AppResult<UploadSessionSummary> =
        suspendRunCatching {
            clientFactory.getClient().post(UploadRoutePaths.SESSIONS).body<UploadSessionSummary>()
        }

    override suspend fun uploadFile(
        sessionId: String,
        relPath: String,
        source: FileSource,
        onProgress: suspend (Long, Long?) -> Unit,
    ): AppResult<UploadSessionSummary> =
        suspendRunCatching {
            clientFactory
                .getClient()
                .submitFormWithBinaryData(
                    url = UploadRoutePaths.file(sessionId),
                    formData =
                        formData {
                            append(
                                key = "file",
                                value = ChannelProvider(source.size) { source.openChannel() },
                                headers =
                                    Headers.build {
                                        append(
                                            HttpHeaders.ContentDisposition,
                                            "filename=\"${source.filename}\"",
                                        )
                                    },
                            )
                        },
                ) {
                    parameter(UploadRoutePaths.REL_PATH_PARAM, relPath)
                    timeout {
                        requestTimeoutMillis = FILE_TRANSFER_TIMEOUT_MS
                        socketTimeoutMillis = FILE_TRANSFER_TIMEOUT_MS
                    }
                    onUpload { sent, total -> onProgress(sent, total) }
                }.body<UploadSessionSummary>()
        }

    override suspend fun finalize(sessionId: String): AppResult<UploadFinalizeResult> =
        suspendRunCatching {
            clientFactory
                .getClient()
                .post(UploadRoutePaths.finalize(sessionId)) {
                    timeout {
                        requestTimeoutMillis = FINALIZE_TIMEOUT_MS
                        socketTimeoutMillis = FINALIZE_TIMEOUT_MS
                    }
                }.body<UploadFinalizeResult>()
        }

    override suspend fun abandon(sessionId: String): AppResult<Unit> =
        suspendRunCatching {
            clientFactory.getClient().delete(UploadRoutePaths.session(sessionId))
        }.map { }
}
