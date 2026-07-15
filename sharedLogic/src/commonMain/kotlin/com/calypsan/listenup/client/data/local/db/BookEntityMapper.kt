package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.ImageStorage

/**
 * Maps the Books-A sync wire payload to a Room [BookEntity], merging server-authoritative
 * fields from [BookSyncPayload] with client-computed fields preserved from an [existing] row.
 *
 * ## Preservation semantics
 *
 * [BookEntity.coverBlurHash] is computed client-side by extracting it from the cover image
 * after download. It is never on the wire. Overwriting it with `null` on every sync update
 * would erase the already-computed BlurHash and cause the cover placeholder to flicker back
 * to its default state until the cover is re-analysed. This mapper preserves it from
 * [existing] so sync updates are visually transparent.
 *
 * ## Children
 *
 * This mapper handles the book root row only. Chapter, contributor, and series rows are
 * the responsibility of `BookMirrorApply`.
 */
internal class BookEntityMapper {
    /**
     * Produce a [BookEntity] by combining server-authoritative fields from [payload] with
     * the client-computed blur-hash field taken from [existing].
     *
     * @param payload The wire snapshot delivered by the sync substrate.
     * @param existing The current [BookEntity] in Room for this book ID, or `null` if this
     *   is the first time the book is being seen on this client. When `null`, the
     *   client-computed BlurHash defaults to `null` (it will be populated once the cover image
     *   is downloaded and analysed).
     */
    fun toBookEntity(
        payload: BookSyncPayload,
        existing: BookEntity?,
    ): BookEntity =
        BookEntity(
            id = BookId(payload.id),
            // Library membership — wire-authoritative, taken from the payload.
            libraryId = payload.libraryId,
            folderId = payload.folderId,
            // Wire-authoritative fields — always taken from the payload.
            title = payload.title,
            sortTitle = payload.sortTitle,
            subtitle = payload.subtitle,
            description = payload.description,
            publishYear = payload.publishYear,
            publisher = payload.publisher,
            language = payload.language,
            isbn = payload.isbn,
            asin = payload.asin,
            abridged = payload.abridged,
            bookTierLabel = payload.bookTierLabel,
            partTierLabel = payload.partTierLabel,
            totalDuration = payload.totalDuration,
            coverHash = payload.cover?.hash,
            // Client-computed field — preserved from the existing row so that a sync update
            // never discards the BlurHash already extracted from the cover image on this device.
            // When existing is null (first-seen book) it is null and will be populated after the
            // cover image is downloaded and analysed.
            coverBlurHash = existing?.coverBlurHash,
            // Client-local cover-presence marker — preserved like coverBlurHash, except when the
            // server's cover hash changed: BookSyncDomainHandler deletes the stale local file in the
            // same upsert flow, so presence must reset until the new cover is downloaded.
            coverDownloadedAt = existing?.takeIf { it.coverHash == payload.cover?.hash }?.coverDownloadedAt,
            // Wire-authoritative per-field edit provenance — the server owns this set (it unions an
            // edited field on every edit RPC), so a sync update always takes the payload's value.
            userEditedFields = payload.userEditedFields,
            // Sync substrate fields.
            revision = payload.revision,
            deletedAt = payload.deletedAt,
            hasScanWarning = payload.hasScanWarning,
            // Timestamps: payload carries epoch-ms Longs; BookEntity uses the Timestamp value class.
            createdAt = Timestamp(payload.createdAt),
            updatedAt = Timestamp(payload.updatedAt),
        )
}

/**
 * Local cover path derived from the persisted cover-presence marker — pure string
 * construction, no filesystem stat. Null when no local cover file is recorded.
 */
internal fun ImageStorage.coverPathFor(
    bookId: BookId,
    coverDownloadedAt: Timestamp?,
): String? = coverDownloadedAt?.let { getCoverPath(bookId) }

/**
 * Convert an [AudioFileEntity] to the domain [AudioFile].
 *
 * Single canonical mapper — used by both [BookWithContributors.toDetail] and [PlaybackPreparer].
 */
internal fun AudioFileEntity.toAudioFile(): AudioFile =
    AudioFile(
        id = id,
        index = index,
        filename = filename,
        format = format,
        codec = codec,
        duration = duration,
        size = size,
        codecProfile = codecProfile,
        spatial = spatial,
        bitrate = bitrate,
        sampleRate = sampleRate,
        channels = channels,
    )

private const val ROLE_AUTHOR = "author"
private const val ROLE_NARRATOR = "narrator"

/**
 * Extract contributors by role from cross-references, preserving creditedAs attributions.
 *
 * When contributors are merged, their original credited name may differ from their canonical name.
 * This function uses creditedAs from the cross-reference when available, falling back to the
 * contributor's canonical name.
 *
 * @param role The role to filter by (e.g., "author", "narrator")
 * @param contributorsById Lookup map of contributor ID to ContributorEntity
 * @return List of Contributors for the specified role
 */
internal fun List<BookContributorCrossRef>.extractByRole(
    role: String,
    contributorsById: Map<ContributorId, ContributorEntity>,
): List<BookContributor> =
    filter { it.role == role }
        .mapNotNull { crossRef ->
            contributorsById[crossRef.contributorId]?.let { entity ->
                BookContributor(entity.id.value, crossRef.creditedAs ?: entity.name)
            }
        }.distinctBy { it.id }

/**
 * Convert BookWithContributors to domain BookListItem for list/shelf/home surfaces.
 *
 * Returns the list-shaped projection — no genres, tags, or allContributors. Use
 * [toDetail] when those fields are required.
 *
 * @param hasDocuments Whether the book has at least one supplementary document row in
 *   [book_documents]. Callers that resolve this from a combined DAO query pass the computed
 *   value here; callers that do not need the flag leave it at the default of `false`.
 */
internal fun BookWithContributors.toListItem(
    imageStorage: ImageStorage,
    hasDocuments: Boolean = false,
): BookListItem {
    val contributorsById = contributors.associateBy { it.id }

    val authors = contributorRoles.extractByRole(ROLE_AUTHOR, contributorsById)
    val narrators = contributorRoles.extractByRole(ROLE_NARRATOR, contributorsById)

    val seriesById = series.associateBy { it.id }
    val bookSeriesList =
        seriesSequences.mapNotNull { seq ->
            seriesById[seq.seriesId]?.let { seriesEntity ->
                BookSeries(
                    seriesId = seriesEntity.id.value,
                    seriesName = seriesEntity.name,
                    sequence = seq.sequence,
                )
            }
        }

    return BookListItem(
        id = book.id,
        libraryId = book.libraryId,
        folderId = book.folderId,
        title = book.title,
        sortTitle = book.sortTitle,
        subtitle = book.subtitle,
        authors = authors,
        narrators = narrators,
        duration = book.totalDuration,
        coverPath = imageStorage.coverPathFor(book.id, book.coverDownloadedAt),
        coverHash = book.coverHash,
        coverBlurHash = book.coverBlurHash,
        addedAt = book.createdAt,
        updatedAt = book.updatedAt,
        description = book.description,
        series = bookSeriesList,
        publishYear = book.publishYear,
        publisher = book.publisher,
        language = book.language,
        isbn = book.isbn,
        asin = book.asin,
        abridged = book.abridged,
        rating = null,
        hasDocuments = hasDocuments,
    )
}

/**
 * Convert BookWithContributors to domain BookDetail for the detail screen.
 *
 * Computes [BookDetail.allContributors] via dedup-and-role-aggregation — same
 * algorithm as the now-deleted private mapper in BookRepositoryImpl.
 * Genres, tags, and moods are loaded externally and passed through.
 */
internal fun BookWithContributors.toDetail(
    imageStorage: ImageStorage,
    genres: List<Genre>,
    tags: List<Tag>,
    moods: List<Mood>,
    audioFiles: List<AudioFile> = emptyList(),
): BookDetail {
    val contributorsById = contributors.associateBy { it.id }

    val authors = contributorRoles.extractByRole(ROLE_AUTHOR, contributorsById)
    val narrators = contributorRoles.extractByRole(ROLE_NARRATOR, contributorsById)

    // allContributors: dedupe by id, group all roles, prefer creditedAs name.
    val rolesByContributorId = contributorRoles.groupBy({ it.contributorId }, { it.role })
    val creditedAsByContributorId = contributorRoles.associate { it.contributorId to it.creditedAs }
    val allContributors =
        contributors
            .distinctBy { it.id }
            .map { entity ->
                BookContributor(
                    id = entity.id.value,
                    name = creditedAsByContributorId[entity.id] ?: entity.name,
                    creditedAs = creditedAsByContributorId[entity.id],
                    roles = rolesByContributorId[entity.id] ?: emptyList(),
                )
            }

    val seriesById = series.associateBy { it.id }
    val bookSeriesList =
        seriesSequences.mapNotNull { seq ->
            seriesById[seq.seriesId]?.let { seriesEntity ->
                BookSeries(
                    seriesId = seriesEntity.id.value,
                    seriesName = seriesEntity.name,
                    sequence = seq.sequence,
                )
            }
        }

    return BookDetail(
        id = book.id,
        libraryId = book.libraryId,
        folderId = book.folderId,
        title = book.title,
        sortTitle = book.sortTitle,
        subtitle = book.subtitle,
        authors = authors,
        narrators = narrators,
        duration = book.totalDuration,
        coverPath = imageStorage.coverPathFor(book.id, book.coverDownloadedAt),
        coverHash = book.coverHash,
        coverBlurHash = book.coverBlurHash,
        addedAt = book.createdAt,
        updatedAt = book.updatedAt,
        description = book.description,
        series = bookSeriesList,
        publishYear = book.publishYear,
        publisher = book.publisher,
        language = book.language,
        isbn = book.isbn,
        asin = book.asin,
        abridged = book.abridged,
        rating = null,
        allContributors = allContributors,
        genres = genres,
        tags = tags,
        hasScanWarning = book.hasScanWarning,
        moods = moods,
        audioFiles = audioFiles,
    )
}
