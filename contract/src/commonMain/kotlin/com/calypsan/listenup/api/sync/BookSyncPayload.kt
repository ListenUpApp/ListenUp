package com.calypsan.listenup.api.sync

import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Fat-snapshot wire DTO for a single book and all of its child rows
 * (contributors, series memberships, chapters, audio files).
 *
 * One payload carries the whole aggregate — clients don't issue follow-up
 * fetches per child. Implements [Tombstoned] so the substrate's soft-delete
 * routing applies uniformly.
 *
 * Wire-stable: `@SerialName("BookSyncPayload")` is the polymorphic discriminator
 * used by SSE events and catch-up pages. Field renames break wire compatibility;
 * additions are forward-compatible thanks to `contractJson { ignoreUnknownKeys = true }`.
 */
@Serializable
@SerialName("BookSyncPayload")
data class BookSyncPayload(
    override val id: String,
    /** The library this book belongs to. */
    val libraryId: LibraryId,
    /** The folder within [libraryId] where this book's files were discovered. */
    val folderId: FolderId,
    val title: String,
    val sortTitle: String?,
    val subtitle: String?,
    val description: String?,
    val publishYear: Int?,
    val publisher: String?,
    val language: String?,
    val isbn: String?,
    val asin: String?,
    val abridged: Boolean,
    val explicit: Boolean,
    val hasScanWarning: Boolean = false,
    val totalDuration: Long,
    val cover: CoverPayload?,
    val rootRelPath: String,
    val inode: Long?,
    val scannedAt: Long,
    val contributors: List<BookContributorPayload>,
    val series: List<BookSeriesPayload>,
    @SerialName("genres") val genres: List<BookGenrePayload> = emptyList(),
    val audioFiles: List<BookAudioFilePayload>,
    val documents: List<BookDocumentPayload> = emptyList(),
    val chapters: List<BookChapterPayload>,
    /** Provenance of [chapters]; [ChapterSource.USER] is rescan-protected. Defaults to EMBEDDED for forward-compat. */
    val chapterSource: ChapterSource = ChapterSource.EMBEDDED,
    /**
     * The book's own vocabulary for its OUTER chapter-grouping tier — e.g. "Book" or "Volume".
     * Pairs with the per-chapter [BookChapterPayload.bookTitle]. Null means the tier is unnamed;
     * the UI then shows the chapter's grouping value with no type chip, or offers to name it.
     * Defaults to null for forward-compat with payloads produced by older server versions.
     */
    @SerialName("bookTierLabel") val bookTierLabel: String? = null,
    /**
     * The book's own vocabulary for its INNER chapter-grouping tier — e.g. "Part" or "Sequence".
     * Pairs with the per-chapter [BookChapterPayload.partTitle]. Same null semantics as
     * [bookTierLabel].
     */
    @SerialName("partTierLabel") val partTierLabel: String? = null,
    /**
     * The scalar/collection metadata fields the user has hand-edited in the app — each is
     * rescan-protected. For a field in this set, a rescan preserves the existing DB value instead of
     * overwriting it with the value re-derived from the files/sidecars. Empty on scanner-produced
     * payloads; populated by the edit API. Defaults to empty for forward-compat. Chapters and covers
     * carry their own provenance ([ChapterSource.USER] / [CoverSource.UPLOADED]) and are not listed here.
     */
    val userEditedFields: Set<UserEditedField> = emptySet(),
    override val revision: Long,
    val updatedAt: Long,
    val createdAt: Long,
    override val deletedAt: Long?,
) : SyncPayload

/**
 * Contributor credit on a book — author, narrator, translator, etc.
 *
 * `id` resolves to a server-side `ContributorTable` row; `creditedAs` allows
 * one-off display variants ("J.K. Rowling writing as Robert Galbraith") without
 * forking the contributor identity.
 */
@Serializable
@SerialName("BookContributorPayload")
data class BookContributorPayload(
    val id: String,
    val name: String,
    val sortName: String?,
    val role: String,
    val creditedAs: String?,
)

/**
 * Series membership for a book — at most one row per series per book.
 *
 * `sequence` is a free-form string ("1", "1.5", "Book Zero") because Audible
 * and Goodreads disagree on numbering conventions.
 */
@Serializable
@SerialName("BookSeriesPayload")
data class BookSeriesPayload(
    val id: String,
    val name: String,
    val sequence: String?,
)

/**
 * One audio file on disk that contributes to a book's playback.
 *
 * `index` mirrors the on-disk order so multi-file books play correctly.
 * `format` and `codec` are informational source-file metadata surfaced to the
 * playback pipeline. The optional audio-stream fields (`codecProfile`, `spatial`,
 * `bitrate`, `sampleRate`, `channels`) default to null for back-compat with
 * payloads produced by older server versions.
 */
@Serializable
@SerialName("BookAudioFilePayload")
data class BookAudioFilePayload(
    val id: String,
    val index: Int,
    val filename: String,
    val format: String,
    val codec: String,
    val duration: Long,
    val size: Long,
    /** AAC profile token (`lc`/`he`/`hev2`/`xhe`); null for non-AAC or when undetermined. */
    val codecProfile: String? = null,
    /** Spatial-audio marker (e.g. `atmos`); null when not spatial. */
    val spatial: String? = null,
    /** Average bitrate in bits/sec; null when undetermined. */
    val bitrate: Int? = null,
    /** Sample rate in Hz; null when undetermined. */
    val sampleRate: Int? = null,
    /** Channel count; null when undetermined. */
    val channels: Int? = null,
)

/**
 * A supplementary document (ebook) that ships with a book — served at
 * `GET /books/{bookId}/documents/{id}`.
 *
 * `filename` is the book-root-relative path; clients display its basename.
 * `format` is the file extension in lowercase (e.g. "pdf", "epub").
 * `hash` is the SHA-256 hex digest of the file contents, used for cache-busting
 * and integrity checks.
 */
@Serializable
@SerialName("BookDocumentPayload")
data class BookDocumentPayload(
    val id: String,
    val index: Int,
    val filename: String,
    val format: String,
    val size: Long,
    val hash: String,
)

/**
 * One chapter inside a book.
 *
 * `startTime` is offset from the start of the book (not the start of any
 * individual audio file). `duration` lets clients render progress bars without
 * computing the next chapter's `startTime`.
 */
@Serializable
@SerialName("BookChapterPayload")
data class BookChapterPayload(
    val id: String,
    val title: String,
    val duration: Long,
    val startTime: Long,
    /** Non-null on the chapter that opens a Part. Free text; null = not a Part start. */
    val partTitle: String? = null,
    /** Non-null on the chapter that opens a Book. May co-occur with [partTitle]. */
    val bookTitle: String? = null,
)

/**
 * Cover semantics on the wire — source + content hash.
 *
 * The URL is constructed client-side from the server base URL + book ID + hash.
 * This keeps the wire portable across server URL changes (mDNS local vs
 * remote, port changes, container migrations) and bakes cache-busting into the
 * resource address: different `hash` → different URL → image loader fetches
 * fresh bytes naturally.
 */
@Serializable
@SerialName("CoverPayload")
data class CoverPayload(
    val source: CoverSource,
    val hash: String,
)

/**
 * Where a book's chapter set came from — the provenance that makes user edits
 * rescan-safe.
 *
 * [EMBEDDED] chapters were parsed from audio-file metadata at scan time.
 * [AUDNEXUS] chapters were looked up from Audible/Audnexus.
 * [USER] chapters were hand-edited in the chapter editor. A book whose source is
 * [USER] is never overwritten by a rescan — the scanner preserves the existing
 * chapter set. Re-import is an explicit user action.
 */
@Serializable
enum class ChapterSource {
    EMBEDDED,
    AUDNEXUS,
    USER,
}

/**
 * A book metadata field whose user-applied value is rescan-protected.
 *
 * A field lands here when the user either hand-edits it in the app or applies provider enrichment
 * (Audible/Audnexus) to it — both are deliberate user choices the scanner must not silently revert.
 * The field is recorded in [BookSyncPayload.userEditedFields], so a later rescan preserves the stored
 * value instead of re-deriving it from the files/sidecars and clobbering it. Chapters and covers carry
 * their own provenance ([ChapterSource.USER] / [CoverSource.UPLOADED]) and are not part of this set.
 */
@Serializable
enum class UserEditedField {
    TITLE,
    SUBTITLE,
    DESCRIPTION,
    CONTRIBUTORS,
    SERIES,
    PUBLISHER,
    LANGUAGE,
    PUBLISH_YEAR,
    GENRES,
}

/**
 * Where the cover bytes live on the server.
 *
 * [FILESYSTEM] covers are standalone image files in the book's folder.
 * [EMBEDDED] covers are artwork embedded inside an audio file.
 * [UPLOADED] covers are user-supplied custom images uploaded via the API.
 * [ENRICHED] covers are auto-downloaded from a metadata provider.
 * Clients render all sources identically via the `/api/v1/books/{id}/cover`
 * route — the server resolves the source transparently.
 */
@Serializable
enum class CoverSource {
    FILESYSTEM,
    EMBEDDED,
    UPLOADED,
    ENRICHED,
}
