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
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.provider.MetadataProvider
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreHierarchyFromLadder
import com.calypsan.listenup.server.services.SeriesRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

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
 *  - **Genres** (when [MetadataApplySelection.genres] is non-empty) are replaced via the same
 *    3-step cascade as the scanner (alias → [GenreNormalizer] → pending), written BEFORE the
 *    text `upsert` so the upsert's `readPayload` re-reads the junction and the genres ride the
 *    same SSE event + revision bump. An empty set leaves existing genres untouched.
 *  - **Enrichment** (best-effort) writes the user's selected moods + tropes from the apply
 *    [selection] additively (the values were scraped + classified at lookup time and chosen by the
 *    user as chips). Never fails the match (see [applyEnrichmentBestEffort]).
 *  - **Cover** (when selected) downloads the wizard's chosen cover URL and stores it as
 *    [CoverSource.UPLOADED] — an explicit user choice that wins over any existing cover.
 *
 * Returns [MetadataError.NotFound] when the book is absent or the provider has no match.
 * All writes go through `upsert` / `setManagedCover` / `setBookGenres`, so revisions bump
 * and SSE fires.
 */
internal class BookMetadataApplier(
    private val bookRepository: BookRepository,
    private val contributorRepository: ContributorRepository,
    private val seriesRepository: SeriesRepository,
    private val imageStorage: ImageStorage,
    private val coverImageStore: CoverImageStore,
    private val metadataProvider: MetadataProvider,
    private val genreHierarchy: GenreHierarchyFromLadder,
    private val db: Database,
    private val ladderSource: suspend (region: AudibleRegion, asin: String) -> List<List<String>>,
    private val enrichmentDeps: MetadataEnrichmentDeps,
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

            applyGenresBestEffort(bookId, asin, selection)
            applyGenreHierarchyBestEffort(bookId, asin, region, selection)
            applyEnrichmentBestEffort(bookId, asin, selection)

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
                applyChosenCover(
                    bookId = bookId,
                    coverUrl =
                        selection.coverUrl?.takeIf {
                            it.isNotBlank()
                        } ?: match.coverUrlMaxSize ?: match.coverUrl,
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
     * Replaces a role's contributors only when the resolved replacement is non-empty; an empty
     * ASIN set or a non-empty-but-unresolvable set leaves the role untouched. Other roles are
     * never modified.
     */
    private suspend fun mergeContributors(
        existing: List<BookContributorPayload>,
        match: MetadataBook,
        selection: MetadataApplySelection,
    ): List<BookContributorPayload> {
        val merged = existing.toMutableList()
        val authors = match.authors.selected(selection.authorAsins).resolve(ContributorRole.AUTHOR)
        if (authors.isNotEmpty()) {
            merged.removeAll { it.role.equals(ContributorRole.AUTHOR.apiValue, ignoreCase = true) }
            merged += authors
        }
        val narrators = match.narrators.selected(selection.narratorAsins).resolve(ContributorRole.NARRATOR)
        if (narrators.isNotEmpty()) {
            merged.removeAll { it.role.equals(ContributorRole.NARRATOR.apiValue, ignoreCase = true) }
            merged += narrators
        }
        return merged
    }

    /** Replaces series only when the resolved set is non-empty; unresolvable ASINs leave series untouched. */
    private suspend fun mergeSeries(
        existing: List<BookSeriesPayload>,
        match: MetadataBook,
        selection: MetadataApplySelection,
    ): List<BookSeriesPayload> {
        val resolved = match.series.filter { it.asin != null && it.asin in selection.seriesAsins }.resolveSeries()
        return resolved.ifEmpty { existing }
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
     * Applies [selection]'s genres to [bookId] via [BookRepository.setBookGenres] when the selection
     * is non-empty; no-ops otherwise. Best-effort: a failure is logged and skipped so text metadata
     * already committed is not rolled back. [CancellationException] is always re-raised.
     */
    private suspend fun applyGenresBestEffort(
        bookId: BookId,
        asin: String,
        selection: MetadataApplySelection,
    ) {
        if (selection.genres.isEmpty()) return
        try {
            bookRepository.setBookGenres(bookId, selection.genres.toList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Genre apply failed for ${bookId.value} (ASIN $asin) — skipping" }
        }
    }

    /**
     * Nests the matched book's genres from Audible's category ladders and links the book to every
     * rung (root → leaf), so browsing a parent genre surfaces the book and the leaf is its most
     * specific genre. Obtains the structured ladders server-side via [ladderSource] (the wire
     * [MetadataBook] only carries the flat dedup). Runs AFTER [applyGenresBestEffort] (whose
     * `setBookGenres` wipes the junction first) so the additive rung links survive, and BEFORE the
     * book `upsert` so the re-read junction rides the same SSE event + revision bump.
     *
     * Best-effort: a failure is logged and skipped so already-committed metadata is not rolled back.
     * [CancellationException] is always re-raised. No-ops when the match has no ladders.
     */
    private suspend fun applyGenreHierarchyBestEffort(
        bookId: BookId,
        asin: String,
        region: AudibleRegion,
        selection: MetadataApplySelection,
    ) {
        if (selection.genres.isEmpty()) return
        try {
            val ladders = ladderSource(region, asin)
            if (ladders.isEmpty()) return

            for (ladder in ladders) {
                val rungIds = genreHierarchy.ensureLadder(ladder)
                if (rungIds.isEmpty()) continue
                suspendTransaction(db) {
                    for (rungId in rungIds) {
                        BookGenreTable.insertIfAbsent(bookId.value, rungId)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Genre-hierarchy apply failed for ${bookId.value} (ASIN $asin) — skipping" }
        }
    }

    /**
     * Writes the user's selected moods and tropes from [selection] additively through
     * [BookMoodWriter][com.calypsan.listenup.server.services.BookMoodWriter] and
     * [BookTagWriter][com.calypsan.listenup.server.services.BookTagWriter]. Both writers are
     * add-only — existing moods/tags are never wiped.
     *
     * The moods/tags were scraped + classified at lookup time (see
     * `MetadataLookupServiceImpl.enrichWithMoodsAndTags`) and presented to the user as toggleable
     * chips; the apply path honors that selection rather than re-scraping, so a deselected mood/tag
     * is never written. An empty set for a dimension writes nothing for it.
     *
     * Best-effort: a write error is logged and skipped so the already-committed match is never
     * rolled back. [CancellationException] is always re-raised. No-ops when both sets are empty.
     *
     * Limitation (#573): re-matching a book ACCUMULATES moods/tropes — the writers are add-only,
     * so a second apply adds the new selection's tags on top of the first's. A future
     * selective-apply surface (#573) is the fix; for now accumulation is the accepted shape.
     */
    private suspend fun applyEnrichmentBestEffort(
        bookId: BookId,
        asin: String,
        selection: MetadataApplySelection,
    ) {
        if (selection.moods.isEmpty() && selection.tags.isEmpty()) return
        try {
            if (selection.moods.isNotEmpty()) {
                enrichmentDeps.bookMoodWriter.writeMoods(bookId, selection.moods.toList())
            }
            if (selection.tags.isNotEmpty()) {
                enrichmentDeps.bookTagWriter.writeScanTags(bookId, selection.tags.toList())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Audible enrichment write failed for ${bookId.value} (ASIN $asin) — skipping" }
        }
    }

    /**
     * Downloads [coverUrl] and stores it as the book's managed cover ([CoverSource.UPLOADED]) — a
     * wizard cover selection is an explicit user choice that wins over any existing cover (matching
     * the dedicated `applyCover`). Best-effort: the text metadata is already committed, so a failed
     * or invalid cover is logged and skipped, never failing the apply. Binary I/O stays outside the
     * text transaction; an orphan from a failed [setManagedCover][BookRepository.setManagedCover] is
     * reaped by OrphanImageCleanupTask. [CancellationException] is re-raised.
     */
    private suspend fun applyChosenCover(
        bookId: BookId,
        coverUrl: String?,
        asin: String,
    ) {
        val url = coverUrl?.takeIf { it.isNotBlank() } ?: return
        try {
            val bytes = imageStorage.downloadBytes(url)
            val stored = coverImageStore.store.store(bookId.value, bytes, "image/jpeg")
            val relPath = "covers/${stored.path.fileName}"
            val result = bookRepository.setManagedCover(bookId, relPath, stored.sha256, CoverSource.UPLOADED)
            if (result is AppResult.Success) {
                log.info { "Stored wizard-chosen cover for ${bookId.value} → $relPath" }
            } else {
                log.warn { "setManagedCover failed for ${bookId.value}: $result" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Wizard cover download/store failed for ${bookId.value} (ASIN $asin) — skipping" }
        }
    }

    /** Parses the leading 4-digit year from an Audible release-date string (`"2015-06-02"` → `2015`). */
    private fun parseYear(releaseDate: String?): Int? = releaseDate?.take(YEAR_DIGITS)?.toIntOrNull()

    private companion object {
        const val YEAR_DIGITS = 4
    }
}
