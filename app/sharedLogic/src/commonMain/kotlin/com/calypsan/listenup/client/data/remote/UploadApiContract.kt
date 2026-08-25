package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.dto.uploads.UploadFinalizeResult
import com.calypsan.listenup.api.dto.uploads.UploadSessionSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FileSource

/**
 * The four calls of the admin book-upload surface — the one library write that cannot ride RPC.
 *
 * An upload is a **session**: mint one, stream each file into it separately, then finalize the
 * whole staged tree in a single call. One request per file rather than one N-part request is the
 * server's choice (it reuses the proven single-file multipart path on both the JVM and
 * Kotlin/Native runtimes); the client benefit is that per-file retry falls out for free.
 *
 * Split from its implementation so the session orchestration in `UploadRepositoryImpl` — the loop,
 * the byte accounting, the abandon-on-failure — is testable without an HTTP engine.
 */
internal interface UploadApiContract {
    /** Mints a new upload session. The returned summary starts at zero files, zero bytes. */
    suspend fun createSession(): AppResult<UploadSessionSummary>

    /**
     * Streams one file into [sessionId] at [relPath], relative to what the user selected.
     *
     * [onProgress] reports `(bytesSent, totalBytes)` as the body drains — `totalBytes` is null when
     * the source could not report a size. It fires on the caller's coroutine.
     *
     * Answers the session's updated totals, so the caller never keeps a parallel tally.
     */
    suspend fun uploadFile(
        sessionId: String,
        relPath: String,
        source: FileSource,
        onProgress: suspend (Long, Long?) -> Unit,
    ): AppResult<UploadSessionSummary>

    /**
     * Ingests the staged tree — one [com.calypsan.listenup.api.dto.uploads.UploadedBook] per book
     * the server's grouper found in it. Never all-or-nothing: one book can be refused as a
     * duplicate while its neighbours land.
     */
    suspend fun finalize(sessionId: String): AppResult<UploadFinalizeResult>

    /** Abandons [sessionId] and removes its staging directory. Best-effort cleanup after a failure. */
    suspend fun abandon(sessionId: String): AppResult<Unit>
}
