package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.uploads.UploadFinalizeResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.core.FileSource
import kotlinx.coroutines.flow.Flow

/**
 * One file the user selected, ready to stream.
 *
 * [relPath] is the file's path **relative to what the user picked**: a bare filename when they
 * chose loose files, or a path preserving subdirectories when they chose a folder. The client
 * transmits the structure faithfully and guesses nothing about how many books it represents —
 * grouping is the server's job, where the audio tags already are.
 */
data class UploadCandidate(
    val relPath: String,
    val source: FileSource,
)

/** Where an upload has got to. Exactly one of [Done] or [Failed] ever terminates the stream. */
sealed interface UploadStep {
    /**
     * A file is on the wire. [bytesSent] is the running total across the whole session, so a
     * progress bar over `bytesSent / totalBytes` moves smoothly across file boundaries instead of
     * resetting at each one.
     */
    data class Staging(
        val fileIndex: Int,
        val fileCount: Int,
        val filename: String,
        val bytesSent: Long,
        val totalBytes: Long,
    ) : UploadStep

    /** Every file is staged; the server is moving them into the library and starting ingest. */
    data object Finalizing : UploadStep

    /** The session finished. Individual books inside [result] may still be duplicates or failures. */
    data class Done(
        val result: UploadFinalizeResult,
    ) : UploadStep

    /** The session failed as a whole. Staging has been abandoned server-side. */
    data class Failed(
        val error: AppError,
    ) : UploadStep
}

/** Uploads books into the library. Admin-only; the server enforces that, not the client. */
interface UploadRepository {
    /**
     * Streams [candidates] into one upload session and finalizes it, reporting progress as it goes.
     *
     * Cold: nothing is sent until collected, and cancelling the collector abandons the session
     * rather than leaving a staging directory behind on the server.
     */
    fun upload(candidates: List<UploadCandidate>): Flow<UploadStep>
}
