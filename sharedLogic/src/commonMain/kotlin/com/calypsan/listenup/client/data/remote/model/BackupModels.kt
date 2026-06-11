package com.calypsan.listenup.client.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// === ABS Import Models ===

/**
 * Request to analyze an ABS backup.
 */
@Serializable
data class AnalyzeABSRequest(
    @SerialName("backup_path") val backupPath: String,
    @SerialName("match_by_email") val matchByEmail: Boolean = true,
    @SerialName("match_by_path") val matchByPath: Boolean = true,
    @SerialName("fuzzy_match_books") val fuzzyMatchBooks: Boolean = true,
    @SerialName("fuzzy_threshold") val fuzzyThreshold: Double = 0.85,
    @SerialName("user_mappings") val userMappings: Map<String, String>? = null,
    @SerialName("book_mappings") val bookMappings: Map<String, String>? = null,
)

/**
 * Suggestion for user mapping.
 */
@Serializable
data class ABSUserSuggestion(
    @SerialName("user_id") val userId: String,
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val score: Double,
    val reason: String,
)

/**
 * User match result from ABS analysis.
 */
@Serializable
data class ABSUserMatch(
    @SerialName("abs_user_id") val absUserId: String,
    @SerialName("abs_username") val absUsername: String,
    @SerialName("abs_email") val absEmail: String? = null,
    @SerialName("listenup_id") val listenupId: String? = null,
    val confidence: String,
    @SerialName("match_reason") val matchReason: String? = null,
    val suggestions: List<ABSUserSuggestion> = emptyList(),
)

/**
 * Suggestion for book mapping.
 */
@Serializable
data class ABSBookSuggestion(
    @SerialName("book_id") val bookId: String,
    val title: String,
    val author: String? = null,
    @SerialName("duration_ms") val durationMs: Long,
    val score: Double,
    val reason: String,
)

/**
 * Book match result from ABS analysis.
 */
@Serializable
data class ABSBookMatch(
    @SerialName("abs_item_id") val absItemId: String,
    @SerialName("abs_title") val absTitle: String,
    @SerialName("abs_author") val absAuthor: String? = null,
    @SerialName("listenup_id") val listenupId: String? = null,
    val confidence: String,
    @SerialName("match_reason") val matchReason: String? = null,
    val suggestions: List<ABSBookSuggestion> = emptyList(),
)

/**
 * Response from ABS backup analysis.
 */
@Serializable
data class AnalyzeABSResponse(
    @SerialName("backup_path") val backupPath: String,
    @SerialName("analyzed_at") val analyzedAt: String,
    val summary: String,
    @SerialName("total_users") val totalUsers: Int,
    @SerialName("total_books") val totalBooks: Int,
    @SerialName("total_sessions") val totalSessions: Int,
    @SerialName("users_matched") val usersMatched: Int,
    @SerialName("users_pending") val usersPending: Int,
    @SerialName("books_matched") val booksMatched: Int,
    @SerialName("books_pending") val booksPending: Int,
    @SerialName("sessions_ready") val sessionsReady: Int,
    @SerialName("sessions_pending") val sessionsPending: Int,
    @SerialName("progress_ready") val progressReady: Int,
    @SerialName("progress_pending") val progressPending: Int,
    @SerialName("user_matches") val userMatches: List<ABSUserMatch>,
    @SerialName("book_matches") val bookMatches: List<ABSBookMatch>,
    val warnings: List<String> = emptyList(),
)

/**
 * Response from starting an async ABS analysis.
 */
@Serializable
data class AsyncAnalyzeResponse(
    @SerialName("analysis_id") val analysisId: String,
)

/**
 * Response from polling async ABS analysis status.
 */
@Serializable
data class AnalysisStatusResponse(
    val status: String, // "running", "completed", "failed"
    val phase: String, // "parsing", "matching_users", "matching_books", etc.
    val current: Int = 0,
    val total: Int = 0,
    @SerialName("total_books") val totalBooks: Int = 0,
    @SerialName("total_users") val totalUsers: Int = 0,
    val result: AnalyzeABSResponse? = null,
    val error: String? = null,
)

/**
 * Request to import from an ABS backup.
 */
@Serializable
data class ImportABSRequest(
    @SerialName("backup_path") val backupPath: String,
    @SerialName("user_mappings") val userMappings: Map<String, String>,
    @SerialName("book_mappings") val bookMappings: Map<String, String>,
    @SerialName("import_sessions") val importSessions: Boolean = true,
    @SerialName("import_progress") val importProgress: Boolean = true,
    @SerialName("rebuild_progress") val rebuildProgress: Boolean = true,
)

/**
 * Response from ABS import operation.
 */
@Serializable
data class ImportABSResponse(
    @SerialName("sessions_imported") val sessionsImported: Int,
    @SerialName("sessions_skipped") val sessionsSkipped: Int,
    @SerialName("progress_imported") val progressImported: Int,
    @SerialName("progress_skipped") val progressSkipped: Int,
    @SerialName("events_created") val eventsCreated: Int,
    @SerialName("affected_users") val affectedUsers: Int,
    val duration: String,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)
