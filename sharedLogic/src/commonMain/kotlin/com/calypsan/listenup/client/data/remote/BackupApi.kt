package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.client.data.remote.model.AnalysisStatusResponse
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSRequest
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSResponse
import com.calypsan.listenup.client.data.remote.model.AsyncAnalyzeResponse
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
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
 * Implementation of the Audiobookshelf-import and filesystem-browse API using Ktor.
 */
internal class BackupApi(
    private val clientFactory: ApiClientFactory,
) : BackupApiContract {
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
}
