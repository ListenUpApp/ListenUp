package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.core.FileSource
import com.calypsan.listenup.client.data.remote.model.AnalysisStatusResponse
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSRequest
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSResponse
import com.calypsan.listenup.client.data.remote.model.AsyncAnalyzeResponse
import com.calypsan.listenup.client.data.remote.model.BackupResponse
import com.calypsan.listenup.client.data.remote.model.ImportABSRequest
import com.calypsan.listenup.client.data.remote.model.ImportABSResponse
import com.calypsan.listenup.client.data.remote.model.RebuildProgressResponse
import com.calypsan.listenup.client.data.remote.model.RestoreRequest
import com.calypsan.listenup.client.data.remote.model.RestoreResponse
import com.calypsan.listenup.client.data.remote.model.ValidationResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * API contract for backup and restore operations.
 * All operations require admin authentication.
 */
interface BackupApiContract {
    /**
     * Create a new backup.
     *
     * @param includeImages Include cover images and avatars (increases size)
     * @param includeEvents Include listening events (required for history)
     * @return Backup metadata
     */
    suspend fun createBackup(
        includeImages: Boolean = false,
        includeEvents: Boolean = true,
    ): BackupResponse

    /**
     * List all available backups.
     */
    suspend fun listBackups(): List<BackupResponse>

    /**
     * Get details for a specific backup.
     */
    suspend fun getBackup(id: String): BackupResponse

    /**
     * Delete a backup.
     */
    suspend fun deleteBackup(id: String)

    /**
     * Validate a backup without restoring.
     */
    suspend fun validateBackup(backupId: String): ValidationResponse

    /**
     * Restore from a backup.
     */
    suspend fun restore(request: RestoreRequest): RestoreResponse

    /**
     * Rebuild all playback progress from listening events.
     */
    suspend fun rebuildProgress(): RebuildProgressResponse

    // === Filesystem Browsing ===

    /**
     * Browse the server filesystem to find backup files.
     *
     * @param path Directory path to browse (use "/" for root)
     * @return Directory listing with entries
     */
    suspend fun browseFilesystem(path: String): BrowseFilesystemResponse

    // === ABS Import ===

    /**
     * Upload an Audiobookshelf backup file using streaming.
     *
     * The file content is streamed directly from the source without loading
     * the entire file into memory. This is critical for large backup files
     * that could otherwise cause out-of-memory crashes.
     *
     * @param fileSource Streaming source for the backup file content
     * @return The server path where the file was saved
     */
    suspend fun uploadABSBackup(fileSource: FileSource): UploadABSBackupResponse

    /**
     * Analyze an Audiobookshelf backup.
     */
    suspend fun analyzeABSBackup(request: AnalyzeABSRequest): AnalyzeABSResponse

    /**
     * Start an async analysis of an Audiobookshelf backup.
     */
    suspend fun analyzeABSBackupAsync(request: AnalyzeABSRequest): AsyncAnalyzeResponse

    /**
     * Poll the status of an async analysis.
     */
    suspend fun getAnalysisStatus(analysisId: String): AnalysisStatusResponse

    /**
     * Import from an Audiobookshelf backup.
     */
    suspend fun importABSBackup(request: ImportABSRequest): ImportABSResponse
}

/**
 * Response from uploading an ABS backup.
 */
@Serializable
data class UploadABSBackupResponse(
    @SerialName("path")
    val path: String,
)
