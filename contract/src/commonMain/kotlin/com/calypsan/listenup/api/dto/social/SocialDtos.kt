package com.calypsan.listenup.api.dto.social

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One other user's live "currently listening" session, as seen by a viewer.
 *
 * Book identity is carried as [bookId] only; the client enriches title/cover from its local
 * library (which holds exactly the books the viewer can access). Identity is projected from
 * `public_profiles`. The server returns only sessions for books the caller can access.
 *
 * @property userId The listening user.
 * @property displayName The listening user's public display name.
 * @property avatarType `"auto"` or `"image"`.
 * @property bookId The book being listened to (guaranteed caller-accessible).
 * @property startedAtMs Epoch-ms the session began.
 */
@Serializable
@SerialName("CurrentlyListeningSession")
data class CurrentlyListeningSession(
    val userId: String,
    val displayName: String,
    val avatarType: String,
    val bookId: String,
    val startedAtMs: Long,
)

/**
 * One other user currently reading a given book, as seen by a viewer who can access that book.
 *
 * @property userId The reading user.
 * @property displayName The reading user's public display name.
 * @property avatarType `"auto"` or `"image"`.
 * @property startedAtMs Epoch-ms the session began.
 */
@Serializable
@SerialName("BookReader")
data class BookReader(
    val userId: String,
    val displayName: String,
    val avatarType: String,
    val startedAtMs: Long,
)
