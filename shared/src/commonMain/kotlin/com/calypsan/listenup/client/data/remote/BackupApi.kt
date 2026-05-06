package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.FileSource
import com.calypsan.listenup.client.core.Success
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
import com.calypsan.listenup.client.core.error.AppException
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
    ): BackupResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<BackupResponse> =
            client
                .post("/api/v1/admin/backups") {
                    setBody(
                        CreateBackupRequest(
                            includeImages = includeImages,
                            includeEvents = includeEvents,
                        ),
                    )
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun listBackups(): List<BackupResponse> {
        val client = clientFactory.getClient()
        val response: ApiResponse<List<BackupResponse>> =
            client.get("/api/v1/admin/backups").body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun getBackup(id: String): BackupResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<BackupResponse> =
            client.get("/api/v1/admin/backups/$id").body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun deleteBackup(id: String) {
        val client = clientFactory.getClient()
        val response: ApiResponse<Unit> =
            client.delete("/api/v1/admin/backups/$id").body()

        when (val result = response.toResult()) {
            is Success -> { /* Backup deleted successfully */ }

            is Failure -> {
                throw AppException(result.error)
            }
        }
    }

    override suspend fun validateBackup(backupId: String): ValidationResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<ValidationResponse> =
            client
                .post("/api/v1/admin/backups/validate") {
                    setBody(ValidateBackupRequest(backupId = backupId))
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun restore(request: RestoreRequest): RestoreResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<RestoreResponse> =
            client
                .post("/api/v1/admin/restore") {
                    setBody(request)
                    // Full restore can take significant time to wipe and reimport all data
                    timeout {
                        requestTimeoutMillis = 5 * 60 * 1000
                        socketTimeoutMillis = 5 * 60 * 1000
                    }
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun rebuildProgress(): RebuildProgressResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<RebuildProgressResponse> =
            client.post("/api/v1/admin/rebuild-progress").body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    // === Filesystem Browsing ===

    override suspend fun browseFilesystem(path: String): BrowseFilesystemResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<BrowseFilesystemResponse> =
            client
                .get("/api/v1/filesystem") {
                    url {
                        parameters.append("path", path)
                    }
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    // === ABS Import ===

    override suspend fun uploadABSBackup(fileSource: FileSource): UploadABSBackupResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<UploadABSBackupResponse> =
            client
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
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun analyzeABSBackup(request: AnalyzeABSRequest): AnalyzeABSResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<AnalyzeABSResponse> =
            client
                .post("/api/v1/admin/abs/analyze") {
                    setBody(request)
                    // Parsing large SQLite databases can take time (5 minutes)
                    timeout {
                        requestTimeoutMillis = 5 * 60 * 1000
                        socketTimeoutMillis = 5 * 60 * 1000
                    }
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun analyzeABSBackupAsync(request: AnalyzeABSRequest): AsyncAnalyzeResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<AsyncAnalyzeResponse> =
            client
                .post("/api/v1/admin/abs/analyze/async") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun getAnalysisStatus(analysisId: String): AnalysisStatusResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<AnalysisStatusResponse> =
            client.get("/api/v1/admin/abs/analyze/$analysisId/status").body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun importABSBackup(request: ImportABSRequest): ImportABSResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<ImportABSResponse> =
            client
                .post("/api/v1/admin/abs/import") {
                    setBody(request)
                    // Import can process many items (5 minutes)
                    timeout {
                        requestTimeoutMillis = 5 * 60 * 1000
                        socketTimeoutMillis = 5 * 60 * 1000
                    }
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }
}
