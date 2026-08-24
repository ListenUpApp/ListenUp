package com.calypsan.listenup.api.dto.organize

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * The organizer's folder-structure preset — curated shapes, not a free template language.
 * Mirrors the server's planner presets one-to-one.
 */
@Serializable
enum class OrganizePreset {
    /** `Author/Title` — no series folder, ever. */
    AUTHOR_TITLE,

    /** `Author/Series/Title` when the book belongs to a series, else `Author/Title`. */
    AUTHOR_SERIES_TITLE,

    /** `Title` — a single flat segment, no author or series folder. */
    FLAT_TITLE,
}

/** How a book's series-index number is prefixed onto its title segment (`Book 1 - ` / `1 - ` / `[1] ` / none). */
@Serializable
enum class OrganizeSeriesPrefix {
    /** `Book 1 - Title`. */
    BOOK_N_DASH,

    /** `1 - Title`. */
    N_DASH,

    /** `[1] Title`. */
    BRACKET_N,

    /** No prefix — just `Title`. */
    NONE,
}

/** How the primary author's display name renders into a folder segment. */
@Serializable
enum class OrganizeAuthorForm {
    /** `Brandon Sanderson`. */
    FIRST_LAST,

    /** `Sanderson, Brandon`. */
    LAST_FIRST,
}

/**
 * The admin's organizer schema, as read/written through
 * [com.calypsan.listenup.api.OrganizeService]. [enabled] gates the whole feature: while `false`
 * nothing on disk moves and metadata edits don't relocate books; the preset/knob values persist
 * regardless so a re-enable previews against the last-chosen schema.
 */
@Serializable
data class OrganizeSettingsDto(
    @SerialName("enabled")
    val enabled: Boolean = false,
    @SerialName("preset")
    val preset: OrganizePreset = OrganizePreset.AUTHOR_SERIES_TITLE,
    @SerialName("seriesPrefix")
    val seriesPrefix: OrganizeSeriesPrefix = OrganizeSeriesPrefix.BOOK_N_DASH,
    @SerialName("authorForm")
    val authorForm: OrganizeAuthorForm = OrganizeAuthorForm.FIRST_LAST,
)

/** One before→after row of an organize preview — the browsable move list in the save-confirm dialog. */
@Serializable
data class OrganizePreviewEntryDto(
    @SerialName("bookId")
    val bookId: String,
    @SerialName("fromPath")
    val fromPath: String,
    @SerialName("toPath")
    val toPath: String,
    @SerialName("collisionResolved")
    val collisionResolved: Boolean,
)

/**
 * The consent-dialog summary for a pending organize run: **"moves [fileCount] files across
 * [bookCount] folders; [collisionCount] collisions resolved"** plus the first
 * [entries] rows ([truncated] signals more exist than were returned).
 */
@Serializable
data class OrganizePreviewDto(
    @SerialName("bookCount")
    val bookCount: Int,
    @SerialName("fileCount")
    val fileCount: Int,
    @SerialName("collisionCount")
    val collisionCount: Int,
    @SerialName("entries")
    val entries: List<OrganizePreviewEntryDto>,
    @SerialName("truncated")
    val truncated: Boolean,
)

/** Opaque identity of one organize run — returned by `saveAndExecute`, consumed by `observeRun`. */
@Serializable
@JvmInline
value class OrganizeRunId(
    val value: String,
)

/**
 * Server-pushed progress of an organize run. A run emits [Started] once, then one [BookMoved] or
 * [BookFailed] per planned book, then exactly one terminal [Completed]. Failed books don't stop
 * the run (Never Stranded: completed books stay; the report carries the failures).
 */
@Serializable
sealed interface OrganizeRunEvent {
    /** The run began; [totalBooks] books are planned to move. */
    @Serializable
    @SerialName("OrganizeRunEvent.Started")
    data class Started(
        @SerialName("runId")
        val runId: OrganizeRunId,
        @SerialName("totalBooks")
        val totalBooks: Int,
    ) : OrganizeRunEvent

    /** One book relocated successfully. [completed] counts all attempted books so far (moved + failed). */
    @Serializable
    @SerialName("OrganizeRunEvent.BookMoved")
    data class BookMoved(
        @SerialName("bookId")
        val bookId: String,
        @SerialName("toPath")
        val toPath: String,
        @SerialName("completed")
        val completed: Int,
        @SerialName("totalBooks")
        val totalBooks: Int,
    ) : OrganizeRunEvent

    /** One book's move failed; its files stay consistent (journaled) and the run continues. */
    @Serializable
    @SerialName("OrganizeRunEvent.BookFailed")
    data class BookFailed(
        @SerialName("bookId")
        val bookId: String,
        @SerialName("reason")
        val reason: String,
        @SerialName("completed")
        val completed: Int,
        @SerialName("totalBooks")
        val totalBooks: Int,
    ) : OrganizeRunEvent

    /** Terminal report: the run finished with [movedBooks] relocated and [failedBooks] left for a resume. */
    @Serializable
    @SerialName("OrganizeRunEvent.Completed")
    data class Completed(
        @SerialName("movedBooks")
        val movedBooks: Int,
        @SerialName("failedBooks")
        val failedBooks: Int,
    ) : OrganizeRunEvent
}
