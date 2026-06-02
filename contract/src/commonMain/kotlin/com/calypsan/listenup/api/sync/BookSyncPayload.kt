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
    val id: String,
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
    val chapters: List<BookChapterPayload>,
    val revision: Long,
    val updatedAt: Long,
    val createdAt: Long,
    override val deletedAt: Long?,
) : Tombstoned

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
 * `format` and `codec` are informational — clients use them to decide whether
 * to request a transcode.
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
