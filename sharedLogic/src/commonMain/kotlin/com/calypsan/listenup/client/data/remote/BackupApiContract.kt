package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.client.data.remote.model.AnalysisStatusResponse
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSRequest
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSResponse
import com.calypsan.listenup.client.data.remote.model.AsyncAnalyzeResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * API contract for the Audiobookshelf-import and filesystem-browse surface.
 * All operations require admin authentication.
 */
interface BackupApiContract {
    // === Filesystem Browsing ===

    /**
     * Browse the server filesystem to find backup files.
     *
     * @param path Directory path to browse (use "/" for root)
     * @return [AppResult] wrapping a directory listing with entries.
     */
    suspend fun browseFilesystem(path: String): AppResult<BrowseFilesystemResponse>

    // === ABS Import ===

    /**
     * Upload an Audiobookshelf backup file using streaming.
     *
     * The file content is streamed directly from the source without loading
     * the entire file into memory. This is critical for large backup files
     * that could otherwise cause out-of-memory crashes.
     *
     * @param fileSource Streaming source for the backup file content
     * @return [AppResult] wrapping the server path where the file was saved.
     */
    suspend fun uploadABSBackup(fileSource: FileSource): AppResult<UploadABSBackupResponse>

    /**
     * Analyze an Audiobookshelf backup.
     */
    suspend fun analyzeABSBackup(request: AnalyzeABSRequest): AppResult<AnalyzeABSResponse>

    /**
     * Start an async analysis of an Audiobookshelf backup.
     */
    suspend fun analyzeABSBackupAsync(request: AnalyzeABSRequest): AppResult<AsyncAnalyzeResponse>

    /**
     * Poll the status of an async analysis.
     */
    suspend fun getAnalysisStatus(analysisId: String): AppResult<AnalysisStatusResponse>
}

/**
 * Response from uploading an ABS backup.
 */
@Serializable
data class UploadABSBackupResponse(
    @SerialName("path")
    val path: String,
)
