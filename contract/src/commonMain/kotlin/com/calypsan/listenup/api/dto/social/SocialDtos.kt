package com.calypsan.listenup.api.dto.social

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One other person's row in the "What Others Are Listening To" section, as seen by a viewer.
 *
 * A row is either **live** ([isLive] `true` — that user has an open listening session right now)
 * or a **recent fill** ([isLive] `false` — nobody is listening, so the section shows the book they
 * most recently played and did not finish). With a handful of users the live set is empty most of
 * the time, and a section that hides itself when empty is indistinguishable from a broken one; the
 * fill is what keeps the surface honest.
 *
 * The two kinds differ in *why* their timestamp exists, not in *whether* one does — so there is a
 * single non-null [lastActiveAtMs] rather than a pair of mutually-exclusive nullable fields. That
 * leaves no illegal wire state to guard against and no field whose name lies for half the rows.
 *
 * Book identity is carried as [bookId] only; the client enriches title/cover from its local
 * library (which holds exactly the books the viewer can access). Identity is projected from
 * `public_profiles`. The server returns at most one row per user, always excludes the caller, and
 * returns only books the caller can access — live rows and recent rows alike.
 *
 * @property userId The other user.
 * @property displayName Their public display name.
 * @property avatarType `"auto"` or `"image"`.
 * @property bookId The book to show for them (guaranteed caller-accessible).
 * @property lastActiveAtMs Epoch-ms this user was last active on [bookId]: the session start when
 *   [isLive], otherwise the position's `lastPlayedAt`. Orders the section, and is what a non-live
 *   row renders as a relative time.
 * @property isLive True when the user is listening right now; the row renders "Listening now"
 *   instead of a relative time.
 */
@Serializable
@SerialName("CurrentlyListeningSession")
data class CurrentlyListeningSession(
    @SerialName("userId") val userId: String,
    @SerialName("displayName") val displayName: String,
    @SerialName("avatarType") val avatarType: String,
    @SerialName("bookId") val bookId: String,
    @SerialName("lastActiveAtMs") val lastActiveAtMs: Long,
    @SerialName("isLive") val isLive: Boolean,
)

/**
 * One reader of a book: their live progress (if reading) plus their dated finish history.
 *
 * [hardcoverFinishes] are reads the user logged on Hardcover that ListenUp pulled (#601 B3). They are
 * kept apart from [finishes] on purpose: a client that predates the field never shows a Hardcover
 * read at all, rather than showing one without its "Hardcover" badge.
 */
@Serializable
@SerialName("BookReaderEntry")
data class BookReaderEntry(
    val userId: String,
    val displayName: String,
    val avatarType: String,
    /** 0..100 when the user has an in-progress (unfinished) position; null otherwise. */
    val currentProgressPct: Int?,
    /** ListenUp finishes, finished_at epoch ms, newest-first; empty when the user is only currently reading. */
    val finishes: List<Long>,
    /** Hardcover reads, finished_at epoch ms, newest-first; empty when the user has none. */
    val hardcoverFinishes: List<Long> = emptyList(),
    /**
     * The [finishes] that were also logged on Hardcover, newest-first — a subset of [finishes]. Each
     * was paired 1:1 with a Hardcover read within three calendar days of it in the reader's home
     * timezone, and that read is left out of [hardcoverFinishes], so one listen is one row badged
     * "Also on Hardcover" rather than two. Empty from a server that predates the pairing.
     */
    @SerialName("finishesAlsoOnHardcover")
    val finishesAlsoOnHardcover: List<Long> = emptyList(),
)

/** The full readership of a book: everyone (incl. the caller) who is reading or has finished it. */
@Serializable
@SerialName("BookReadership")
data class BookReadership(
    val readers: List<BookReaderEntry>,
)
