@file:Suppress("SwallowedException")

package com.calypsan.listenup.client.data.remote.model

import com.calypsan.listenup.client.data.remote.BookReadersResponse
import com.calypsan.listenup.client.data.remote.ReaderSummary
import com.calypsan.listenup.client.data.remote.ReadingHistorySession
import com.calypsan.listenup.client.data.remote.SessionSummary
import com.calypsan.listenup.client.data.remote.UserReadingHistoryResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for SessionSummary.
 *
 * Maps to domain SessionSummary in ApiContracts.kt.
 */
@Serializable
internal data class SessionSummaryResponse(
    @SerialName("id")
    val id: String,
    @SerialName("started_at")
    val startedAt: String,
    @SerialName("finished_at")
    val finishedAt: String? = null,
    @SerialName("is_completed")
    val isCompleted: Boolean,
    @SerialName("listen_time_ms")
    val listenTimeMs: Long,
) {
    fun toDomain(): SessionSummary =
        SessionSummary(
            id = id,
            startedAt = startedAt,
            finishedAt = finishedAt,
            isCompleted = isCompleted,
            listenTimeMs = listenTimeMs,
        )
}

/**
 * Wire format for ReaderSummary.
 *
 * Maps to domain ReaderSummary in ApiContracts.kt.
 */
@Serializable
internal data class ReaderSummaryResponse(
    @SerialName("user_id")
    val userId: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("avatar_type")
    val avatarType: String = "auto",
    @SerialName("avatar_value")
    val avatarValue: String? = null,
    @SerialName("avatar_color")
    val avatarColor: String,
    @SerialName("is_currently_reading")
    val isCurrentlyReading: Boolean,
    @SerialName("current_progress")
    val currentProgress: Double = 0.0,
    @SerialName("started_at")
    val startedAt: String,
    @SerialName("finished_at")
    val finishedAt: String? = null,
    @SerialName("last_activity_at")
    val lastActivityAt: String,
    @SerialName("completion_count")
    val completionCount: Int,
) {
    fun toDomain(): ReaderSummary =
        ReaderSummary(
            userId = userId,
            displayName = displayName,
            avatarType = avatarType,
            avatarValue = avatarValue,
            avatarColor = avatarColor,
            isCurrentlyReading = isCurrentlyReading,
            currentProgress = currentProgress,
            startedAt = startedAt,
            finishedAt = finishedAt,
            lastActivityAt = lastActivityAt,
            completionCount = completionCount,
        )
}

/**
 * Wire format for BookReadersResponse.
 *
 * Maps to domain BookReadersResponse in ApiContracts.kt.
 */
@Serializable
internal data class BookReadersApiResponse(
    @SerialName("your_sessions")
    val yourSessions: List<SessionSummaryResponse>,
    @SerialName("other_readers")
    val otherReaders: List<ReaderSummaryResponse>,
    @SerialName("total_readers")
    val totalReaders: Int,
    @SerialName("total_completions")
    val totalCompletions: Int,
) {
    fun toDomain(): BookReadersResponse =
        BookReadersResponse(
            yourSessions = yourSessions.map { it.toDomain() },
            otherReaders = otherReaders.map { it.toDomain() },
            totalReaders = totalReaders,
            totalCompletions = totalCompletions,
        )
}

/**
 * Wire format for ReadingHistorySession.
 *
 * Maps to domain ReadingHistorySession in ApiContracts.kt.
 */
@Serializable
internal data class ReadingHistorySessionResponse(
    @SerialName("id")
    val id: String,
    @SerialName("book_id")
    val bookId: String,
    @SerialName("book_title")
    val bookTitle: String,
    @SerialName("book_author")
    val bookAuthor: String = "",
    @SerialName("cover_path")
    val coverPath: String? = null,
    @SerialName("started_at")
    val startedAt: String,
    @SerialName("finished_at")
    val finishedAt: String? = null,
    @SerialName("is_completed")
    val isCompleted: Boolean,
    @SerialName("listen_time_ms")
    val listenTimeMs: Long,
) {
    fun toDomain(): ReadingHistorySession =
        ReadingHistorySession(
            id = id,
            bookId = bookId,
            bookTitle = bookTitle,
            bookAuthor = bookAuthor,
            coverPath = coverPath,
            startedAt = startedAt,
            finishedAt = finishedAt,
            isCompleted = isCompleted,
            listenTimeMs = listenTimeMs,
        )
}

/**
 * Wire format for UserReadingHistoryResponse.
 *
 * Maps to domain UserReadingHistoryResponse in ApiContracts.kt.
 */
@Serializable
internal data class UserReadingHistoryApiResponse(
    @SerialName("sessions")
    val sessions: List<ReadingHistorySessionResponse>,
    @SerialName("total_sessions")
    val totalSessions: Int,
    @SerialName("total_completed")
    val totalCompleted: Int,
) {
    fun toDomain(): UserReadingHistoryResponse =
        UserReadingHistoryResponse(
            sessions = sessions.map { it.toDomain() },
            totalSessions = totalSessions,
            totalCompleted = totalCompleted,
        )
}

/**
 * Wire format for GET /api/v1/users/me response.
 *
 * Contains current authenticated user's profile data.
 */
@Serializable
internal data class CurrentUserApiResponse(
    @SerialName("id")
    val id: String,
    @SerialName("email")
    val email: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("first_name")
    val firstName: String? = null,
    @SerialName("last_name")
    val lastName: String? = null,
    @SerialName("is_root")
    val isRoot: Boolean,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("avatar_type")
    val avatarType: String = "auto",
    @SerialName("avatar_value")
    val avatarValue: String? = null,
    @SerialName("avatar_color")
    val avatarColor: String = "#6B7280",
) {
    fun toDomain(): com.calypsan.listenup.client.data.remote.CurrentUserResponse =
        com.calypsan.listenup.client.data.remote.CurrentUserResponse(
            id = id,
            email = email,
            displayName = displayName,
            firstName = firstName,
            lastName = lastName,
            isRoot = isRoot,
            createdAt = parseTimestamp(createdAt),
            updatedAt = parseTimestamp(updatedAt),
            avatarType = avatarType,
            avatarValue = avatarValue,
            avatarColor = avatarColor,
        )

    private fun parseTimestamp(timestamp: String): Long =
        try {
            kotlin.time.Instant
                .parse(timestamp)
                .toEpochMilliseconds()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            0L
        }
}
