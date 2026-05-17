package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.ContributorId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.ImageStorage

/**
 * Maps the Books-A sync wire payload to a Room [BookEntity], merging server-authoritative
 * fields from [BookSyncPayload] with client-computed fields preserved from an [existing] row.
 *
 * ## Preservation semantics
 *
 * Palette colors ([BookEntity.dominantColor], [BookEntity.darkMutedColor],
 * [BookEntity.vibrantColor]) and [BookEntity.coverBlurHash] are computed client-side
 * by extracting them from the cover image after download. They are never on the wire.
 * Overwriting them with `null` on every sync update would erase already-computed palette
 * data and cause the gradient UI behind book covers to flicker back to its default state
 * until the cover is re-analysed. This mapper preserves those fields from [existing] so
 * sync updates are visually transparent.
 *
 * ## Children
 *
 * This mapper handles the book root row only. Chapter, contributor, and series rows are
 * the responsibility of `BookSyncDomainHandler` (Task 27).
 */
class BookEntityMapper {
    /**
     * Produce a [BookEntity] by combining server-authoritative fields from [payload] with
     * client-computed palette/blur-hash fields taken from [existing].
     *
     * @param payload The wire snapshot delivered by the sync substrate.
     * @param existing The current [BookEntity] in Room for this book ID, or `null` if this
     *   is the first time the book is being seen on this client. When `null`, all
     *   client-computed fields default to `null` (they will be populated once the cover image
     *   is downloaded and analysed).
     */
    fun toBookEntity(
        payload: BookSyncPayload,
        existing: BookEntity?,
    ): BookEntity =
        BookEntity(
            id = BookId(payload.id),
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
            totalDuration = payload.totalDuration,
            coverHash = payload.cover?.hash,
            // Client-computed fields — preserved from the existing row so that a sync update
            // never discards palette data already extracted from the cover image on this device.
            // When existing is null (first-seen book) these are all null and will be populated
            // after the cover image is downloaded and analysed.
            dominantColor = existing?.dominantColor,
            darkMutedColor = existing?.darkMutedColor,
            vibrantColor = existing?.vibrantColor,
            coverBlurHash = existing?.coverBlurHash,
            // Sync substrate fields.
            revision = payload.revision,
            deletedAt = payload.deletedAt,
            // Timestamps: payload carries epoch-ms Longs; BookEntity uses the Timestamp value class.
            createdAt = Timestamp(payload.createdAt),
            updatedAt = Timestamp(payload.updatedAt),
        )
}

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
fun List<BookContributorCrossRef>.extractByRole(
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
 */
fun BookWithContributors.toListItem(imageStorage: ImageStorage): BookListItem {
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
        title = book.title,
        sortTitle = book.sortTitle,
        subtitle = book.subtitle,
        authors = authors,
        narrators = narrators,
        duration = book.totalDuration,
        coverPath = if (imageStorage.exists(book.id)) imageStorage.getCoverPath(book.id) else null,
        coverBlurHash = book.coverBlurHash,
        dominantColor = book.dominantColor,
        darkMutedColor = book.darkMutedColor,
        vibrantColor = book.vibrantColor,
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
    )
}

/**
 * Convert BookWithContributors to domain BookDetail for the detail screen.
 *
 * Computes [BookDetail.allContributors] via dedup-and-role-aggregation — same
 * algorithm as the (deleted in Task 12) private mapper in BookRepositoryImpl.
 * Genres and tags are loaded externally and passed through.
 */
fun BookWithContributors.toDetail(
    imageStorage: ImageStorage,
    genres: List<Genre>,
    tags: List<Tag>,
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
        title = book.title,
        sortTitle = book.sortTitle,
        subtitle = book.subtitle,
        authors = authors,
        narrators = narrators,
        duration = book.totalDuration,
        coverPath = if (imageStorage.exists(book.id)) imageStorage.getCoverPath(book.id) else null,
        coverBlurHash = book.coverBlurHash,
        dominantColor = book.dominantColor,
        darkMutedColor = book.darkMutedColor,
        vibrantColor = book.vibrantColor,
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
    )
}
