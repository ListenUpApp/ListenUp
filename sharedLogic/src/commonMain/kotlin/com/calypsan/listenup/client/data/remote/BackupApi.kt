package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.client.data.remote.model.AnalysisStatusResponse
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSRequest
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSResponse
import com.calypsan.listenup.client.data.remote.model.AsyncAnalyzeResponse
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.data.remote.model.BackupResponse
import com.calypsan.listenup.client.data.remote.model.CreateBackupRequest
import com.calypsan.listenup.client.data.remote.model.ImportABSRequest
import com.calypsan.listenup.client.data.remote.model.ImportABSResponse
import com.calypsan.listenup.client.data.remote.model.RebuildProgressResponse
import com.calypsan.listenup.client.data.remote.model.RestoreRequest
import com.calypsan.listenup.client.data.remote.model.RestoreResponse
import com.calypsan.listenup.client.data.remote.model.ValidateBackupRequest
import com.calypsan.listenup.client.data.remote.model.ValidationResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

/**
 * Implementation of backup API using Ktor.
 */
class BackupApi(
    private val clientFactory: ApiClientFactory,
) : BackupApiContract {
    override suspend fun createBackup(
        includeImages: Boolean,
        includeEvents: Boolean,
    ): AppResult<BackupResponse> =
        apiCall(errorMessage = "Create-backup response missing data") {
            clientFactory
                .getClient()
                .post("/api/v1/admin/backups") {
                    setBody(
                        CreateBackupRequest(
                            includeImages = includeImages,
                            includeEvents = includeEvents,
                        ),
                    )
                }.body<ApiResponse<BackupResponse>>()
        }

    override suspend fun listBackups(): AppResult<List<BackupResponse>> =
        apiCall(errorMessage = "List-backups response missing data") {
            clientFactory.getClient().get("/api/v1/admin/backups").body<ApiResponse<List<BackupResponse>>>()
        }

    override suspend fun getBackup(id: String): AppResult<BackupResponse> =
        apiCall(errorMessage = "Backup detail response missing data") {
            clientFactory.getClient().get("/api/v1/admin/backups/$id").body<ApiResponse<BackupResponse>>()
        }

    override suspend fun deleteBackup(id: String): AppResult<Unit> =
        apiCallUnit {
            clientFactory.getClient().delete("/api/v1/admin/backups/$id").body<ApiResponse<Unit>>()
        }

    override suspend fun validateBackup(backupId: String): AppResult<ValidationResponse> =
        apiCall(errorMessage = "Validate-backup response missing data") {
            clientFactory
                .getClient()
                .post("/api/v1/admin/backups/validate") {
                    setBody(ValidateBackupRequest(backupId = backupId))
                }.body<ApiResponse<ValidationResponse>>()
        }

    override suspend fun restore(request: RestoreRequest): AppResult<RestoreResponse> =
        apiCall(errorMessage = "Restore-backup response missing data") {
            clientFactory
                .getClient()
                .post("/api/v1/admin/restore") {
                    setBody(request)
                    // Full restore can take significant time to wipe and reimport all data
                    timeout {
                        requestTimeoutMillis = 5 * 60 * 1000
                        socketTimeoutMillis = 5 * 60 * 1000
                    }
                }.body<ApiResponse<RestoreResponse>>()
        }

    override suspend fun rebuildProgress(): AppResult<RebuildProgressResponse> =
        apiCall(errorMessage = "Rebuild-progress response missing data") {
            clientFactory
                .getClient()
                .post(
                    "/api/v1/admin/rebuild-progress",
                ).body<ApiResponse<RebuildProgressResponse>>()
        }

    // === Filesystem Browsing ===

    override suspend fun browseFilesystem(path: String): AppResult<BrowseFilesystemResponse> =
        apiCall(errorMessage = "Browse-filesystem response missing data") {
            clientFactory
                .getClient()
                .get("/api/v1/filesystem") {
                    url {
                        parameters.append("path", path)
                    }
                }.body<ApiResponse<BrowseFilesystemResponse>>()
        }

    // === ABS Import ===

    override suspend fun uploadABSBackup(fileSource: FileSource): AppResult<UploadABSBackupResponse> =
        apiCall(errorMessage = "ABS upload response missing data") {
            clientFactory
                .getClient()
                .submitFormWithBinaryData(
                    url = "/api/v1/admin/abs/upload",
                    formData =
                        formData {
                            // Use ChannelProvider for streaming upload - content is read on-demand
                            // rather than loading the entire file into memory
                            append(
                                key = "backup",
                                value = ChannelProvider(fileSource.size) { fileSource.openChannel() },
                                headers =
                                    Headers.build {
                                        append(HttpHeaders.ContentDisposition, "filename=\"${fileSource.filename}\"")
                                    },
                            )
                        },
                ) {
                    // Large file uploads need extended timeout (10 minutes for streaming)
                    timeout {
                        requestTimeoutMillis = 10 * 60 * 1000
                        socketTimeoutMillis = 10 * 60 * 1000
                    }
                }.body<ApiResponse<UploadABSBackupResponse>>()
        }

    override suspend fun analyzeABSBackup(request: AnalyzeABSRequest): AppResult<AnalyzeABSResponse> =
        apiCall(errorMessage = "ABS analyze response missing data") {
            clientFactory
                .getClient()
                .post("/api/v1/admin/abs/analyze") {
                    setBody(request)
                    // Parsing large SQLite databases can take time (5 minutes)
                    timeout {
                        requestTimeoutMillis = 5 * 60 * 1000
                        socketTimeoutMillis = 5 * 60 * 1000
                    }
                }.body<ApiResponse<AnalyzeABSResponse>>()
        }

    override suspend fun analyzeABSBackupAsync(request: AnalyzeABSRequest): AppResult<AsyncAnalyzeResponse> =
        apiCall(errorMessage = "ABS async-analyze response missing data") {
            clientFactory
                .getClient()
                .post("/api/v1/admin/abs/analyze/async") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.body<ApiResponse<AsyncAnalyzeResponse>>()
        }

    override suspend fun getAnalysisStatus(analysisId: String): AppResult<AnalysisStatusResponse> =
        apiCall(errorMessage = "ABS analysis-status response missing data") {
            clientFactory
                .getClient()
                .get("/api/v1/admin/abs/analyze/$analysisId/status")
                .body<ApiResponse<AnalysisStatusResponse>>()
        }

    override suspend fun importABSBackup(request: ImportABSRequest): AppResult<ImportABSResponse> =
        apiCall(errorMessage = "ABS import response missing data") {
            clientFactory
                .getClient()
                .post("/api/v1/admin/abs/import") {
                    setBody(request)
                    // Import can process many items (5 minutes)
                    timeout {
                        requestTimeoutMillis = 5 * 60 * 1000
                        socketTimeoutMillis = 5 * 60 * 1000
                    }
                }.body<ApiResponse<ImportABSResponse>>()
        }
}
