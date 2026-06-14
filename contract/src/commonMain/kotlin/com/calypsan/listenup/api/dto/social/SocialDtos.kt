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

/** One reader of a book: their live progress (if reading) plus their dated finish history. */
@Serializable
@SerialName("BookReaderEntry")
data class BookReaderEntry(
    val userId: String,
    val displayName: String,
    val avatarType: String,
    /** 0..100 when the user has an in-progress (unfinished) position; null otherwise. */
    val currentProgressPct: Int?,
    /** finished_at epoch ms, newest-first; empty when the user is only currently reading. */
    val finishes: List<Long>,
)

/** The full readership of a book: everyone (incl. the caller) who is reading or has finished it. */
@Serializable
@SerialName("BookReadership")
data class BookReadership(
    val readers: List<BookReaderEntry>,
)
