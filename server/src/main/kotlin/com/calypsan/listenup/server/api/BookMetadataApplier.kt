package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.dto.MetadataSeriesRef
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
import com.calypsan.listenup.server.metadata.provider.MetadataProvider
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Applies a chosen Audible match to an existing book aggregate, honoring a per-field
 * [MetadataApplySelection].
 *
 * Consumes the provider's wire [MetadataBook] (not the Audible-internal type), so the
 * apply path is source-agnostic. Semantics:
 *  - **Scalar fields** (title, subtitle, description, publisher, language, publishYear via
 *    releaseDate) overwrite the book's value only when their flag is set; deselected fields
 *    are preserved. `asin` is always stamped (it is the match identifier).
 *  - **Contributors** are replaced PER ROLE only when that role's ASIN set is non-empty:
 *    selected authors replace the book's AUTHOR-role entries, selected narrators replace
 *    NARRATOR-role entries; an empty set leaves that role untouched. Other roles are never
 *    touched. Names resolve through [ContributorRepository.resolveOrCreate].
 *  - **Series** are replaced when [MetadataApplySelection.seriesAsins] is non-empty, else
 *    left untouched. Resolved through [SeriesRepository.resolveOrCreate].
 *  - **Cover** (when selected) downloads the match cover and stores it as
 *    [CoverSource.ENRICHED] via [applyEnrichedCoverIfAbsent] (precedence-gated).
 *
 * Returns [MetadataError.NotFound] when the book is absent or the provider has no match.
 * All writes go through `upsert` / `setManagedCover`, so revisions bump and SSE fires.
 */
internal class BookMetadataApplier(
    private val bookRepository: BookRepository,
    private val contributorRepository: ContributorRepository,
    private val seriesRepository: SeriesRepository,
    private val imageStorage: ImageStorage,
    private val coverImageStore: CoverImageStore,
    private val metadataProvider: MetadataProvider,
) {
    suspend fun apply(
        bookId: BookId,
        asin: String,
        region: AudibleRegion,
        selection: MetadataApplySelection,
    ): AppResult<Unit> {
        val existing =
            bookRepository.findById(bookId)
                ?: return AppResult.Failure(
                    MetadataError.NotFound(debugInfo = "Book ${bookId.value} not found in the database."),
                )

        return metadataProvider.getBook(asin, region).flatMap { match ->
            if (match == null) {
                return@flatMap AppResult.Failure(
                    MetadataError.NotFound(debugInfo = "No metadata for ASIN $asin in region $region."),
                )
            }

            val updated =
                existing.copy(
                    title = if (selection.title) match.title else existing.title,
                    subtitle = if (selection.subtitle) match.subtitle else existing.subtitle,
                    description = if (selection.description) match.description else existing.description,
                    publisher = if (selection.publisher) match.publisher else existing.publisher,
                    language = if (selection.language) match.language else existing.language,
                    publishYear = selectedPublishYear(selection, match, existing.publishYear),
                    asin = asin,
                    contributors = mergeContributors(existing.contributors, match, selection),
                    series = mergeSeries(existing.series, match, selection),
                )

            val upsertResult = bookRepository.upsert(updated, clientOpId = null)
            if (upsertResult is AppResult.Failure) return@flatMap upsertResult

            if (selection.cover) {
                applyEnrichedCoverIfAbsent(
                    bookId = bookId,
                    coverUrl = match.coverUrlMaxSize ?: match.coverUrl,
                    existingSource = existing.cover?.source,
                    asin = asin,
                )
            }

            AppResult.Success(Unit)
        }
    }

    /** Release year overwrites only when selected and parseable, else keeps [current]. */
    private fun selectedPublishYear(
        selection: MetadataApplySelection,
        match: MetadataBook,
        current: Int?,
    ): Int? = if (selection.releaseDate) parseYear(match.releaseDate) ?: current else current

    /**
     * Replaces a role's contributors only when that role's ASIN set is non-empty; an empty set
     * leaves the role untouched. Other roles are never modified.
     */
    private suspend fun mergeContributors(
        existing: List<BookContributorPayload>,
        match: MetadataBook,
        selection: MetadataApplySelection,
    ): List<BookContributorPayload> {
        val merged = existing.toMutableList()
        if (selection.authorAsins.isNotEmpty()) {
            merged.removeAll { it.role.equals(ContributorRole.AUTHOR.apiValue, ignoreCase = true) }
            merged += match.authors.selected(selection.authorAsins).resolve(ContributorRole.AUTHOR)
        }
        if (selection.narratorAsins.isNotEmpty()) {
            merged.removeAll { it.role.equals(ContributorRole.NARRATOR.apiValue, ignoreCase = true) }
            merged += match.narrators.selected(selection.narratorAsins).resolve(ContributorRole.NARRATOR)
        }
        return merged
    }

    /** Replaces series with the selected matches only when [MetadataApplySelection.seriesAsins] is non-empty. */
    private suspend fun mergeSeries(
        existing: List<BookSeriesPayload>,
        match: MetadataBook,
        selection: MetadataApplySelection,
    ): List<BookSeriesPayload> =
        if (selection.seriesAsins.isNotEmpty()) {
            match.series.filter { it.asin != null && it.asin in selection.seriesAsins }.resolveSeries()
        } else {
            existing
        }

    private fun List<MetadataContributorRef>.selected(asins: Set<String>): List<MetadataContributorRef> =
        filter { it.asin != null && it.asin in asins }

    private suspend fun List<MetadataContributorRef>.resolve(role: ContributorRole): List<BookContributorPayload> =
        map { ref ->
            // resolveOrCreate derives sort name internally; passing null matches any scanner row
            // for the same person, converging all creation paths on one dedup bucket.
            val id = contributorRepository.resolveOrCreate(ref.name, sortName = null)
            BookContributorPayload(
                id = id.value,
                name = ref.name,
                sortName = null,
                role = role.apiValue,
                creditedAs = null,
            )
        }

    private suspend fun List<MetadataSeriesRef>.resolveSeries(): List<BookSeriesPayload> =
        map { entry ->
            val id = seriesRepository.resolveOrCreate(entry.title)
            BookSeriesPayload(id = id.value, name = entry.title, sequence = entry.sequence)
        }

    /**
     * Downloads [coverUrl] and stores it as a [CoverSource.ENRICHED] managed cover, but only
     * when [existingSource] is null or already ENRICHED — higher-precedence covers (UPLOADED,
     * FILESYSTEM, EMBEDDED) block enrichment. Binary I/O stays outside the text-fields
     * transaction; an orphan from a failed `setManagedCover` is reaped by OrphanImageCleanupTask.
     */
    private suspend fun applyEnrichedCoverIfAbsent(
        bookId: BookId,
        coverUrl: String?,
        existingSource: CoverSource?,
        asin: String,
    ) {
        if (existingSource != null && existingSource != CoverSource.ENRICHED) {
            log.debug {
                "Skipping enriched cover for ${bookId.value}: existing source=$existingSource outranks ENRICHED"
            }
            return
        }
        val url = coverUrl?.takeIf { it.isNotBlank() } ?: return
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

    /** Parses the leading 4-digit year from an Audible release-date string (`"2015-06-02"` → `2015`). */
    private fun parseYear(releaseDate: String?): Int? = releaseDate?.take(YEAR_DIGITS)?.toIntOrNull()

    private companion object {
        const val YEAR_DIGITS = 4
    }
}
