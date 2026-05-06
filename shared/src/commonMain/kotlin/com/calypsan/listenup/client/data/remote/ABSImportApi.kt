package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.FileSource
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.core.error.AppException
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contract for persistent ABS import API operations.
 *
 * This API enables resumable imports:
 * - Create an import from an ABS backup (persisted on server)
 * - Map users and books incrementally
 * - Import ready sessions as mappings are confirmed
 * - Leave and return later - state is preserved
 *
 * All methods require admin authentication.
 */
interface ABSImportApiContract {
    // === Import Management ===

    /**
     * Create a new persistent import from an ABS backup file.
     *
     * The file is uploaded, parsed, and stored on the server.
     * Initial analysis runs to find automatic matches.
     *
     * @param fileSource Streaming source for the backup file
     * @param name User-friendly name for this import
     * @return The created import with summary statistics
     */
    suspend fun createImport(
        fileSource: FileSource,
        name: String,
    ): AppResult<ABSImportResponse>

    /**
     * Create a new persistent import from an existing server path.
     *
     * Use this when the backup has already been uploaded or is
     * already on the server filesystem.
     *
     * @param backupPath Path to the backup file on the server
     * @param name User-friendly name for this import
     * @return The created import with summary statistics
     */
    suspend fun createImportFromPath(
        backupPath: String,
        name: String,
    ): AppResult<ABSImportResponse>

    /**
     * List all imports (active, completed, archived).
     */
    suspend fun listImports(): AppResult<List<ABSImportSummary>>

    /**
     * Get details for a specific import.
     */
    suspend fun getImport(importId: String): AppResult<ABSImportResponse>

    /**
     * Delete an import and all associated data.
     */
    suspend fun deleteImport(importId: String): AppResult<Unit>

    // === User Mapping ===

    /**
     * List users from an ABS import.
     *
     * @param importId Import ID
     * @param filter Filter by mapping status
     * @return List of ABS users with mapping info
     */
    suspend fun listImportUsers(
        importId: String,
        filter: MappingFilter = MappingFilter.ALL,
    ): AppResult<List<ABSImportUser>>

    /**
     * Map an ABS user to a ListenUp user.
     *
     * This updates session statuses - sessions for this user
     * may become "ready" if their book is also mapped.
     *
     * @param importId Import ID
     * @param absUserId ABS user ID (from the backup)
     * @param listenUpId ListenUp user ID to map to
     */
    suspend fun mapUser(
        importId: String,
        absUserId: String,
        listenUpId: String,
    ): AppResult<ABSImportUser>

    /**
     * Clear user mapping.
     *
     * Sessions will become "pending_user" again.
     */
    suspend fun clearUserMapping(
        importId: String,
        absUserId: String,
    ): AppResult<ABSImportUser>

    /**
     * Search ListenUp users for mapping.
     *
     * @param query Search query (matches email, name)
     * @param limit Max results
     * @return Matching users
     */
    suspend fun searchUsers(
        query: String,
        limit: Int = 10,
    ): AppResult<List<UserSearchResult>>

    // === Book Mapping ===

    /**
     * List books from an ABS import.
     *
     * @param importId Import ID
     * @param filter Filter by mapping status
     * @return List of ABS books with mapping info
     */
    suspend fun listImportBooks(
        importId: String,
        filter: MappingFilter = MappingFilter.ALL,
    ): AppResult<List<ABSImportBook>>

    /**
     * Map an ABS book to a ListenUp book.
     *
     * This updates session statuses - sessions for this book
     * may become "ready" if their user is also mapped.
     *
     * @param importId Import ID
     * @param absMediaId ABS media ID (from the backup)
     * @param listenUpId ListenUp book ID to map to
     */
    suspend fun mapBook(
        importId: String,
        absMediaId: String,
        listenUpId: String,
    ): AppResult<ABSImportBook>

    /**
     * Clear book mapping.
     *
     * Sessions will become "pending_book" again.
     */
    suspend fun clearBookMapping(
        importId: String,
        absMediaId: String,
    ): AppResult<ABSImportBook>

    // === Session Management ===

    /**
     * List sessions from an ABS import.
     *
     * @param importId Import ID
     * @param status Filter by session status
     * @return List of sessions with status info
     */
    suspend fun listSessions(
        importId: String,
        status: SessionStatusFilter = SessionStatusFilter.ALL,
    ): AppResult<ABSSessionsResponse>

    /**
     * Import all ready sessions.
     *
     * Creates listening events in ListenUp for sessions where
     * both user and book are mapped.
     *
     * @param importId Import ID
     * @return Import result with counts
     */
    suspend fun importReadySessions(importId: String): AppResult<ImportSessionsResult>

    /**
     * Skip a session (won't be imported).
     *
     * @param importId Import ID
     * @param sessionId Session ID to skip
     * @param reason Optional reason for skipping
     */
    suspend fun skipSession(
        importId: String,
        sessionId: String,
        reason: String? = null,
    ): AppResult<Unit>
}

// === Filter Enums ===

/** Filter for [ABSImportApiContract.listImportUsers] / `listImportBooks` by mapping status. */
enum class MappingFilter {
    ALL,
    MAPPED,
    UNMAPPED,
}

/** Filter for [ABSImportApiContract.listSessions] by session lifecycle status. */
enum class SessionStatusFilter {
    ALL,
    PENDING,
    READY,
    IMPORTED,
    SKIPPED,
}

// === Response Models ===

/**
 * Summary of an ABS import for list views.
 */
@Serializable
data class ABSImportSummary(
    val id: String,
    val name: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("total_users") val totalUsers: Int,
    @SerialName("total_books") val totalBooks: Int,
    @SerialName("total_sessions") val totalSessions: Int,
    @SerialName("users_mapped") val usersMapped: Int,
    @SerialName("books_mapped") val booksMapped: Int,
    @SerialName("sessions_imported") val sessionsImported: Int,
)

/**
 * Full details of an ABS import.
 */
@Serializable
data class ABSImportResponse(
    val id: String,
    val name: String,
    @SerialName("backup_path") val backupPath: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("total_users") val totalUsers: Int,
    @SerialName("total_books") val totalBooks: Int,
    @SerialName("total_sessions") val totalSessions: Int,
    @SerialName("users_mapped") val usersMapped: Int,
    @SerialName("books_mapped") val booksMapped: Int,
    @SerialName("sessions_imported") val sessionsImported: Int,
)

/**
 * An ABS user with mapping status.
 */
@Serializable
data class ABSImportUser(
    @SerialName("abs_user_id") val absUserId: String,
    @SerialName("abs_username") val absUsername: String,
    @SerialName("abs_email") val absEmail: String = "",
    @SerialName("listenup_id") val listenUpId: String? = null,
    @SerialName("listenup_email") val listenUpEmail: String? = null,
    @SerialName("listenup_display_name") val listenUpDisplayName: String? = null,
    @SerialName("session_count") val sessionCount: Int = 0,
    @SerialName("total_listen_ms") val totalListenMs: Long = 0,
    val confidence: String = "",
    @SerialName("match_reason") val matchReason: String = "",
    val suggestions: List<String> = emptyList(),
    @SerialName("is_mapped") val isMapped: Boolean = false,
)

/**
 * An ABS book with mapping status.
 */
@Serializable
data class ABSImportBook(
    @SerialName("abs_media_id") val absMediaId: String,
    @SerialName("abs_title") val absTitle: String,
    @SerialName("abs_author") val absAuthor: String = "",
    @SerialName("abs_narrator") val absNarrator: String = "",
    @SerialName("abs_duration_ms") val absDurationMs: Long = 0,
    @SerialName("listenup_id") val listenUpId: String? = null,
    @SerialName("listenup_title") val listenUpTitle: String? = null,
    @SerialName("listenup_author") val listenUpAuthor: String? = null,
    @SerialName("session_count") val sessionCount: Int = 0,
    val confidence: String = "",
    @SerialName("match_reason") val matchReason: String = "",
    val suggestions: List<String> = emptyList(),
    @SerialName("is_mapped") val isMapped: Boolean = false,
)

/**
 * An ABS session with import status.
 */
@Serializable
data class ABSImportSession(
    @SerialName("abs_session_id") val absSessionId: String,
    @SerialName("abs_user_id") val absUserId: String,
    @SerialName("abs_media_id") val absMediaId: String,
    @SerialName("start_time") val startTime: String,
    val duration: Long,
    @SerialName("start_position") val startPosition: Long,
    @SerialName("end_position") val endPosition: Long,
    val status: String,
    @SerialName("imported_at") val importedAt: String? = null,
    @SerialName("skip_reason") val skipReason: String? = null,
)

/**
 * Response from listing sessions.
 */
@Serializable
data class ABSSessionsResponse(
    val sessions: List<ABSImportSession>,
    val summary: SessionsSummary,
) {
    // Convenience accessors matching the UI expectations
    val totalCount: Int get() = summary.total
    val pendingCount: Int get() = summary.pending
    val readyCount: Int get() = summary.ready
    val importedCount: Int get() = summary.imported
    val skippedCount: Int get() = summary.skipped
}

/** Per-status session counts that back the convenience accessors on [ABSSessionsResponse]. */
@Serializable
data class SessionsSummary(
    val total: Int = 0,
    val pending: Int = 0,
    val ready: Int = 0,
    val imported: Int = 0,
    val skipped: Int = 0,
)

/**
 * Result of importing ready sessions.
 */
@Serializable
data class ImportSessionsResult(
    @SerialName("sessions_imported") val sessionsImported: Int = 0,
    @SerialName("events_created") val eventsCreated: Int = 0,
    val duration: String = "",
) {
    // Convenience accessor for UI
    val imported: Int get() = sessionsImported
}

/**
 * User search result for mapping UI.
 */
@Serializable
data class UserSearchResult(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
)

// === API Request Models ===

@Serializable
private data class CreateImportRequest(
    @SerialName("backup_path") val backupPath: String,
    val name: String,
)

@Serializable
private data class MapUserRequest(
    @SerialName("listenup_id") val listenUpId: String,
)

@Serializable
private data class MapBookRequest(
    @SerialName("listenup_id") val listenUpId: String,
)

@Serializable
private data class SkipSessionRequest(
    val reason: String? = null,
)

// === API Response Wrappers ===

@Serializable
private data class ImportListResponse(
    val imports: List<ABSImportSummary>,
)

@Serializable
private data class UsersListResponse(
    val users: List<ABSImportUser>,
)

@Serializable
private data class BooksListResponse(
    val books: List<ABSImportBook>,
)

@Serializable
private data class UserSearchResponse(
    val users: List<UserSearchResult>,
)

// === Implementation ===

/**
 * Implementation of persistent ABS import API.
 */
class ABSImportApi(
    private val clientFactory: ApiClientFactory,
) : ABSImportApiContract {

    // === Import Management ===

    override suspend fun createImport(
        fileSource: FileSource,
        name: String,
    ): AppResult<ABSImportResponse> {
        return try {
            val client = clientFactory.getClient()

            // First upload the file
            val uploadResponse: ApiResponse<UploadABSBackupResponse> =
                client.submitFormWithBinaryData(
                    url = "/api/v1/admin/abs/upload",
                    formData = formData {
                        append(
                            key = "backup",
                            value = ChannelProvider(fileSource.size) { fileSource.openChannel() },
                            headers = Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"${fileSource.filename}\"")
                            },
                        )
                    },
                ) {
                    timeout {
                        requestTimeoutMillis = 10 * 60 * 1000
                        socketTimeoutMillis = 10 * 60 * 1000
                    }
                }.body()

            val uploadPath = when (val result = uploadResponse.toResult()) {
                is Success -> result.data.path
                is Failure -> return Failure(AppException(result.error))
            }

            // Then create the import from the uploaded path
            createImportFromPath(uploadPath, name)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            ErrorBus.emit(ImportError.UploadFailed(debugInfo = e.message))
            Failure(e)
        }
    }

    override suspend fun createImportFromPath(
        backupPath: String,
        name: String,
    ): AppResult<ABSImportResponse> {
        return try {
            val client = clientFactory.getClient()
            val response: ApiResponse<ABSImportResponse> =
                client.post("/api/v1/admin/abs/imports") {
                    setBody(CreateImportRequest(backupPath = backupPath, name = name))
                    timeout {
                        requestTimeoutMillis = 30 * 1000
                        socketTimeoutMillis = 30 * 1000
                    }
                }.body()

            when (val result = response.toResult()) {
                is Success -> Success(result.data)
                is Failure -> Failure(AppException(result.error))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            ErrorBus.emit(ImportError.UploadFailed(debugInfo = e.message))
            Failure(e)
        }
    }

    override suspend fun listImports(): AppResult<List<ABSImportSummary>> {
        return try {
            val client = clientFactory.getClient()
            val response: ApiResponse<ImportListResponse> =
                client.get("/api/v1/admin/abs/imports").body()

            when (val result = response.toResult()) {
                is Success -> Success(result.data.imports)
                is Failure -> Failure(AppException(result.error))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Failure(e)
        }
    }

    override suspend fun getImport(importId: String): AppResult<ABSImportResponse> {
        return try {
            val client = clientFactory.getClient()
            val response: ApiResponse<ABSImportResponse> =
                client.get("/api/v1/admin/abs/imports/$importId").body()

            when (val result = response.toResult()) {
                is Success -> Success(result.data)
                is Failure -> Failure(AppException(result.error))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Failure(e)
        }
    }

    override suspend fun deleteImport(importId: String): AppResult<Unit> {
        return try {
            val client = clientFactory.getClient()
            val response: ApiResponse<Unit> =
                client.delete("/api/v1/admin/abs/imports/$importId").body()

            when (val result = response.toResult()) {
                is Success -> Success(Unit)
                is Failure -> Failure(AppException(result.error))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Failure(e)
        }
    }

    // === User Mapping ===

    override suspend fun listImportUsers(
        importId: String,
        filter: MappingFilter,
    ): AppResult<List<ABSImportUser>> {
        return try {
            val client = clientFactory.getClient()
            val response: ApiResponse<UsersListResponse> =
                client.get("/api/v1/admin/abs/imports/$importId/users") {
                    url {
                        parameters.append("filter", filter.name.lowercase())
                    }
                }.body()

            when (val result = response.toResult()) {
                is Success -> Success(result.data.users)
                is Failure -> Failure(AppException(result.error))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            ErrorBus.emit(ImportError.AnalysisFailed(debugInfo = e.message))
            Failure(e)
        }
    }

    override suspend fun mapUser(
        importId: String,
        absUserId: String,
        listenUpId: String,
    ): AppResult<ABSImportUser> {
        return try {
            val client = clientFactory.getClient()
            val response: ApiResponse<ABSImportUser> =
                client.put("/api/v1/admin/abs/imports/$importId/users/$absUserId") {
                    setBody(MapUserRequest(listenUpId = listenUpId))
                }.body()

            when (val result = response.toResult()) {
                is Success -> Success(result.data)
                is Failure -> Failure(AppException(result.error))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            ErrorBus.emit(ImportError.ApplyFailed(debugInfo = e.message))
            Failure(e)
        }
    }

    override suspend fun clearUserMapping(
        importId: String,
        absUserId: String,
    ): AppResult<ABSImportUser> {
        return try {
            val client = clientFactory.getClient()
            val response: ApiResponse<ABSImportUser> =
                client.delete("/api/v1/admin/abs/imports/$importId/users/$absUserId").body()

            when (val result = response.toResult()) {
                is Success -> Success(result.data)
                is Failure -> Failure(AppException(result.error))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Failure(e)
        }
    }

    override suspend fun searchUsers(
        query: String,
        limit: Int,
    ): AppResult<List<UserSearchResult>> {
        return try {
            val client = clientFactory.getClient()
            val response: ApiResponse<UserSearchResponse> =
                client.get("/api/v1/admin/users/search") {
                    url {
                        parameters.append("q", query)
                        parameters.append("limit", limit.toString())
                    }
                }.body()

            when (val result = response.toResult()) {
                is Success -> Success(result.data.users)
                is Failure -> Failure(AppException(result.error))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Failure(e)
        }
    }

    // === Book Mapping ===

    override suspend fun listImportBooks(
        importId: String,
        filter: MappingFilter,
    ): AppResult<List<ABSImportBook>> {
        return try {
            val client = clientFactory.getClient()
            val response: ApiResponse<BooksListResponse> =
                client.get("/api/v1/admin/abs/imports/$importId/books") {
                    url {
                        parameters.append("filter", filter.name.lowercase())
                    }
                }.body()

            when (val result = response.toResult()) {
                is Success -> Success(result.data.books)
                is Failure -> Failure(AppException(result.error))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            ErrorBus.emit(ImportError.AnalysisFailed(debugInfo = e.message))
            Failure(e)
        }
    }

    override suspend fun mapBook(
        importId: String,
        absMediaId: String,
        listenUpId: String,
    ): AppResult<ABSImportBook> {
        return try {
            val client = clientFactory.getClient()
            val response: ApiResponse<ABSImportBook> =
                client.put("/api/v1/admin/abs/imports/$importId/books/$absMediaId") {
                    setBody(MapBookRequest(listenUpId = listenUpId))
                }.body()

            when (val result = response.toResult()) {
                is Success -> Success(result.data)
                is Failure -> Failure(AppException(result.error))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            ErrorBus.emit(ImportError.ApplyFailed(debugInfo = e.message))
            Failure(e)
        }
    }

    override suspend fun clearBookMapping(
        importId: String,
        absMediaId: String,
    ): AppResult<ABSImportBook> {
        return try {
            val client = clientFactory.getClient()
            val response: ApiResponse<ABSImportBook> =
                client.delete("/api/v1/admin/abs/imports/$importId/books/$absMediaId").body()

            when (val result = response.toResult()) {
                is Success -> Success(result.data)
                is Failure -> Failure(AppException(result.error))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            Failure(e)
        }
    }

    // === Session Management ===

    override suspend fun listSessions(
        importId: String,
        status: SessionStatusFilter,
    ): AppResult<ABSSessionsResponse> {
        return try {
            val client = clientFactory.getClient()
            val response: ApiResponse<ABSSessionsResponse> =
                client.get("/api/v1/admin/abs/imports/$importId/sessions") {
                    url {
                        parameters.append("status", status.name.lowercase())
                    }
                }.body()

            when (val result = response.toResult()) {
                is Success -> Success(result.data)
                is Failure -> Failure(AppException(result.error))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            ErrorBus.emit(ImportError.AnalysisFailed(debugInfo = e.message))
            Failure(e)
        }
    }

    override suspend fun importReadySessions(importId: String): AppResult<ImportSessionsResult> {
        return try {
            val client = clientFactory.getClient()
            val response: ApiResponse<ImportSessionsResult> =
                client.post("/api/v1/admin/abs/imports/$importId/sessions/import") {
                    timeout {
                        requestTimeoutMillis = 5 * 60 * 1000
                        socketTimeoutMillis = 5 * 60 * 1000
                    }
                }.body()

            when (val result = response.toResult()) {
                is Success -> Success(result.data)
                is Failure -> Failure(AppException(result.error))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            ErrorBus.emit(ImportError.ApplyFailed(debugInfo = e.message))
            Failure(e)
        }
    }

    override suspend fun skipSession(
        importId: String,
        sessionId: String,
        reason: String?,
    ): AppResult<Unit> {
        return try {
            val client = clientFactory.getClient()
            val response: ApiResponse<Unit> =
                client.put("/api/v1/admin/abs/imports/$importId/sessions/$sessionId/skip") {
                    setBody(SkipSessionRequest(reason = reason))
                }.body()

            when (val result = response.toResult()) {
                is Success -> Success(Unit)
                is Failure -> Failure(AppException(result.error))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            ErrorBus.emit(ImportError.ApplyFailed(debugInfo = e.message))
            Failure(e)
        }
    }
}
