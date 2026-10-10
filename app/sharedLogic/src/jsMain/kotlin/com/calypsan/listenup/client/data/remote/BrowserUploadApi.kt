package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.dto.uploads.UploadSessionSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.BlobFileSource
import com.calypsan.listenup.core.FileSource

/**
 * The browser's [UploadApiContract]: a picked file goes through [XhrUploadTransport], everything
 * else through the shared Ktor [UploadApi].
 *
 * Only the file send differs, because only the file send carries bytes — Ktor's JS engine copies a
 * request body into memory whole, which a session mint, a finalize or an abandon never notice and an
 * audiobook does not survive. A source that is not a [BlobFileSource] still takes the Ktor path, so
 * nothing handed to this API is ever refused for its type.
 */
internal class BrowserUploadApi(
    private val ktor: UploadApiContract,
    private val transport: XhrUploadTransport,
) : UploadApiContract by ktor {
    override suspend fun uploadFile(
        sessionId: String,
        relPath: String,
        source: FileSource,
        onProgress: suspend (Long, Long?) -> Unit,
    ): AppResult<UploadSessionSummary> =
        if (source is BlobFileSource) {
            transport.upload(sessionId, relPath, source.file, source.filename, onProgress)
        } else {
            ktor.uploadFile(sessionId, relPath, source, onProgress)
        }
}
