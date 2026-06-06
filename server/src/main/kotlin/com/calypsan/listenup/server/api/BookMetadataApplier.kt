package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.cover.CoverImageStore
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
 *  - cover image: downloaded via [ImageStorage], validated and stored through
 *    [CoverImageStore], then recorded as [CoverSource.ENRICHED] via
 *    [BookRepository.setManagedCover] — **only** when the book has no existing
 *    managed cover (i.e. `cover_source` is null or already ENRICHED). Higher-
 *    precedence covers (UPLOADED, FILESYSTEM, EMBEDDED) block enrichment.
 *    **BlurHash deferred** (no Kotlin-native JPEG/PNG decoder; coverBlurHash
 *    stays null).
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
    private val coverImageStore: CoverImageStore,
    private val metadataService: MetadataService,
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

            val resolvedAuthors = audibleBook.authors.resolveContributors(role = ContributorRole.AUTHOR.apiValue)
            val resolvedNarrators = audibleBook.narrators.resolveContributors(role = ContributorRole.NARRATOR.apiValue)
            val resolvedSeries = audibleBook.series.resolveSeries()

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
                )

            // Text-metadata upsert first: bumps revision, publishes the text changes.
            val upsertResult = bookRepository.upsert(updated, clientOpId = null)
            if (upsertResult is AppResult.Failure) return@flatMap upsertResult

            // Cover enrichment runs AFTER the upsert so it isn't clobbered by applyBookFields,
            // which nulls cover columns when the payload's cover is null. setManagedCover issues
            // its own revision bump + SyncEvent, so clients receive two events (text, then cover).
            // File I/O stays outside any DB transaction — the orphan is cleaned up by
            // Task 19's OrphanImageCleanupTask if setManagedCover subsequently fails.
            audibleBook.applyEnrichedCoverIfAbsent(bookId, existing.cover?.source)

            AppResult.Success(Unit)
        }
    }

    private suspend fun List<AudibleContributor>.resolveContributors(role: String): List<BookContributorPayload> =
        map { contributor ->
            // AudibleContributor carries only `asin` and `name` — no sort-name field. Pass null
            // so the dedup key falls back to the display name. Deriving a sort name here would
            // produce a different key than the enrichment upsert later writes, risking a duplicate
            // row or a unique-index collision.
            val id = contributorRepository.resolveOrCreate(contributor.name, sortName = null)
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
     * Downloads the Audible cover and stores it as an [CoverSource.ENRICHED] managed cover,
     * but only when [existingSource] is null or already ENRICHED.
     *
     * Precedence rule: ENRICHED is the lowest-precedence managed cover. Any existing
     * non-null, non-ENRICHED source (UPLOADED, FILESYSTEM, EMBEDDED) blocks enrichment
     * so a user-chosen or scanner-discovered cover is never silently replaced.
     *
     * Binary I/O (download + [ImageStore.store]) stays outside the transaction that
     * writes book text fields. If [bookRepository.setManagedCover] subsequently fails,
     * the orphan file is cleaned up by Task 19's OrphanImageCleanupTask.
     */
    private suspend fun AudibleBook.applyEnrichedCoverIfAbsent(
        bookId: BookId,
        existingSource: CoverSource?,
    ) {
        if (existingSource != null && existingSource != CoverSource.ENRICHED) {
            log.debug {
                "Skipping enriched cover for ${bookId.value}: existing source=$existingSource has higher precedence"
            }
            return
        }
        val url = coverUrl.takeIf { it.isNotBlank() } ?: return
        try {
            val bytes = imageStorage.downloadBytes(url)
            val stored = coverImageStore.store.store(bookId.value, bytes, "image/jpeg")
            val relPath = "covers/${stored.path.fileName}"
            val result = bookRepository.setManagedCover(bookId, relPath, stored.sha256, CoverSource.ENRICHED)
            if (result is AppResult.Success) {
                log.info { "Stored enriched cover for ${bookId.value} → $relPath" }
            } else {
                log.warn { "setManagedCover failed for ${bookId.value}: $result" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Enriched cover download/store failed for ${bookId.value} (ASIN $asin) — skipping" }
        }
    }
}
