package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleContributor
import com.calypsan.listenup.server.metadata.audible.AudibleSeriesEntry
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.services.SeriesRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path

private val log = KotlinLogging.logger {}

/**
 * Applies Audible metadata to an existing book aggregate.
 *
 * Fetches the [AudibleBook] for [asin] in [region], then enriches the existing
 * [BookRepository] row in-place:
 *  - title, subtitle, description, publisher, language, releaseDate
 *  - authors / narrators — resolved through [ContributorRepository.resolveOrCreate]
 *    so each contributor gets a stable id; contributors from the old scan record
 *    that also appear in the Audible response are preserved
 *  - series memberships — resolved through [SeriesRepository.resolveOrCreate]
 *  - asin stamp on the book row
 *  - cover image: downloaded via [ImageStorage] to
 *    `{imageHome}/covers/{bookId}.jpg` — **BlurHash deferred** (no
 *    Kotlin-native JPEG/PNG decoder; coverBlurHash stays null). Task 19's
 *    OrphanImageCleanupTask cleans up any orphan file if the DB write rolls back.
 *
 * Returns [MetadataError.NotFound] when the book is absent from the DB (the UI
 * should not offer "apply metadata" for books it doesn't have). Returns the
 * [MetadataError] from [MetadataService] on Audible API failures.
 *
 * All writes go through the substrate's `upsert`, so revisions are bumped and
 * SSE change events are published automatically.
 */
internal class BookMetadataApplier(
    private val bookRepository: BookRepository,
    private val contributorRepository: ContributorRepository,
    private val seriesRepository: SeriesRepository,
    private val imageStorage: ImageStorage,
    private val metadataService: MetadataService,
    private val imageHome: Path,
) {
    suspend fun apply(
        bookId: BookId,
        asin: String,
        region: AudibleRegion,
    ): AppResult<Unit> {
        val existing =
            bookRepository.findById(bookId)
                ?: return AppResult.Failure(
                    MetadataError.NotFound(debugInfo = "Book ${bookId.value} not found in the database."),
                )

        return metadataService.getBook(region, asin).flatMap { audibleBook ->
            if (audibleBook == null) {
                return@flatMap AppResult.Failure(
                    MetadataError.NotFound(debugInfo = "No Audible metadata for ASIN $asin in region $region."),
                )
            }

            val resolvedAuthors = audibleBook.authors.resolveContributors(role = "author")
            val resolvedNarrators = audibleBook.narrators.resolveContributors(role = "narrator")
            val resolvedSeries = audibleBook.series.resolveSeries()

            // Download cover image if available. Write file first; if the subsequent
            // DB upsert fails, Task 19's OrphanImageCleanupTask reclaims the orphan.
            val coverImagePath = audibleBook.downloadCoverImage(bookId)

            val updated =
                existing.copy(
                    title = audibleBook.title,
                    subtitle = audibleBook.subtitle.takeIf { it.isNotBlank() },
                    description = audibleBook.description.takeIf { it.isNotBlank() },
                    publisher = audibleBook.publisher.takeIf { it.isNotBlank() },
                    language = audibleBook.language.takeIf { it.isNotBlank() },
                    asin = asin,
                    contributors = resolvedAuthors + resolvedNarrators,
                    series = resolvedSeries,
                    // cover: BlurHash deferred — no Kotlin-native image decoder.
                    // coverPath is server-derived at serve time (see BookTable comment).
                    // imagePath on contributors is handled separately via coverImagePath.
                )

            if (coverImagePath != null) {
                log.info { "Downloaded cover for book ${bookId.value} → $coverImagePath" }
            }

            bookRepository.upsert(updated, clientOpId = null).flatMap { AppResult.Success(Unit) }
        }
    }

    private suspend fun List<AudibleContributor>.resolveContributors(role: String): List<BookContributorPayload> =
        map { contributor ->
            val id = contributorRepository.resolveOrCreate(contributor.name)
            BookContributorPayload(
                id = id.value,
                name = contributor.name,
                sortName = null,
                role = role,
                creditedAs = null,
            )
        }

    private suspend fun List<AudibleSeriesEntry>.resolveSeries(): List<BookSeriesPayload> =
        map { entry ->
            val id = seriesRepository.resolveOrCreate(entry.name)
            BookSeriesPayload(
                id = id.value,
                name = entry.name,
                sequence = entry.position.takeIf { it.isNotBlank() },
            )
        }

    /**
     * Downloads the cover image for a book from Audible and stores it at
     * `covers/{bookId}.jpg` relative to [imageHome]. Returns the relative
     * path stored in the DB, or `null` when [AudibleBook.coverUrl] is blank or
     * the download fails (failure is logged, not propagated — cover is best-effort).
     */
    private suspend fun AudibleBook.downloadCoverImage(bookId: BookId): String? {
        val url = coverUrl.takeIf { it.isNotBlank() } ?: return null
        val relPath = "covers/${bookId.value}.jpg"
        val dir = Path(imageHome.toString(), "covers")
        return try {
            kotlinx.io.files.SystemFileSystem
                .createDirectories(dir)
            val dest = Path(imageHome.toString(), relPath)
            imageStorage.download(url, dest)
            relPath
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Cover download failed for book ${bookId.value} (ASIN $asin) — skipping" }
            null
        }
    }
}
