package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.dto.MetadataSeriesRef
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreHierarchyFromLadder
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.CancellationException

private val log = loggerFor<BookMetadataApplier>()

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
 *  - **Genres** are reconciled to [MetadataApplySelection.genres] via the same 3-step cascade as
 *    the scanner (alias → [GenreNormalizer] → pending), written BEFORE the text `upsert` so the
 *    upsert's `readPayload` re-reads the junction and the genres ride the same SSE event + revision
 *    bump. An empty set removes all genres.
 *  - **Enrichment** (best-effort) reconciles the book's moods + tropes to the user's selected values
 *    from the apply [selection] (the values were scraped + classified at lookup time and chosen by
 *    the user as chips); an empty set removes all of that dimension's links. Never fails the match
 *    (see [applyEnrichmentBestEffort]).
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
    private val matchSource: suspend (asin: String, region: AudibleRegion) -> AppResult<MetadataBook?>,
    private val enrichmentProvider: String,
    private val genreHierarchy: GenreHierarchyFromLadder,
    private val sqlDb: ListenUpDatabase,
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

        return matchSource(asin, region).flatMap { match ->
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
                    // Stamp ENRICHMENT provenance for every field this apply overwrites so a later
                    // rescan preserves the enriched value instead of reverting it to file-derived data
                    // (A7 — done honestly now: the field records ENRICHMENT/provider, not a fake USER).
                    fieldProvenance =
                        existing.fieldProvenance.stampEnrichment(
                            enrichedFields(selection),
                            enrichmentProvider,
                        ),
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

    /**
     * The fields this apply overwrites — every one is stamped [FieldSourceKind.ENRICHMENT] so a later
     * scan preserves the enriched value. This is also the *consented* set: an interactive apply is
     * selection-as-consent (a ticked field overrides even a USER pin because the apply writes directly,
     * unconditionally). Contributors are stamped per role only when that role's ASIN set is non-empty
     * (an empty set leaves the role untouched — see [mergeContributors]/[mergeSeries]); series/genres
     * only when non-empty. (Background/auto enrichment, when it lands, is the caller that would narrow
     * this consent set so USER/ENRICHMENT fields are skipped by default.)
     */
    private fun enrichedFields(selection: MetadataApplySelection): Set<BookField> =
        buildSet {
            if (selection.title) add(BookField.TITLE)
            if (selection.subtitle) add(BookField.SUBTITLE)
            if (selection.description) add(BookField.DESCRIPTION)
            if (selection.publisher) add(BookField.PUBLISHER)
            if (selection.language) add(BookField.LANGUAGE)
            if (selection.releaseDate) add(BookField.PUBLISH_YEAR)
            if (selection.authorAsins.isNotEmpty()) add(BookField.AUTHORS)
            if (selection.narratorAsins.isNotEmpty()) add(BookField.NARRATORS)
            if (selection.seriesAsins.isNotEmpty()) add(BookField.SERIES)
            if (selection.genres.isNotEmpty()) add(BookField.GENRES)
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
     * Reconciles [bookId]'s genres to exactly [selection]'s chosen values via
     * [BookRepository.setBookGenres] (which wipes the junction then re-links). An empty selection
     * removes all genres (explicit "none" from the review). Best-effort: a failure is logged and
     * skipped so text metadata already committed is not rolled back. [CancellationException] is
     * always re-raised.
     */
    private suspend fun applyGenresBestEffort(
        bookId: BookId,
        asin: String,
        selection: MetadataApplySelection,
    ) {
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
                suspendTransaction(sqlDb) {
                    for (rungId in rungIds) {
                        sqlDb.bookGenresQueries.insertIfAbsent(book_id = bookId.value, genre_id = rungId)
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
     * Reconciles the book's moods and tropes to exactly [selection]'s chosen values (replace, not
     * add) through the match-apply writers
     * [BookMoodWriter.setBookMoods][com.calypsan.listenup.server.services.BookMoodWriter.setBookMoods]
     * and [BookTagWriter.setBookTags][com.calypsan.listenup.server.services.BookTagWriter.setBookTags].
     *
     * The moods/tags are chosen by the user as toggleable chips and ride the apply [selection]; the
     * apply path honors that selection rather than scraping. A re-match swaps the old
     * set for the new — a deselected mood/tag is dropped — and an empty set removes all of that
     * dimension's links (explicit "none" from the review). This stops re-matching from
     * accumulating across applies.
     *
     * Best-effort: a write error is logged and skipped so the already-committed match is never
     * rolled back. [CancellationException] is always re-raised.
     */
    private suspend fun applyEnrichmentBestEffort(
        bookId: BookId,
        asin: String,
        selection: MetadataApplySelection,
    ) {
        try {
            enrichmentDeps.bookMoodWriter.setBookMoods(bookId, selection.moods.toList())
            enrichmentDeps.bookTagWriter.setBookTags(bookId, selection.tags.toList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Mood/tag reconcile failed for ${bookId.value} (ASIN $asin) — skipping" }
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
            val relPath = "covers/${stored.path.name}"
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

    /**
     * Overlays [FieldSourceKind.ENRICHMENT] provenance (with the applying [provider]) for [fields] onto
     * this map, stamped at the current wall clock. Out-ranks a scan, so a rescan preserves the enriched
     * value; the max-tier union in `BookRepository` makes it sticky.
     */
    private fun Map<BookField, FieldProvenance>.stampEnrichment(
        fields: Set<BookField>,
        provider: String,
    ): Map<BookField, FieldProvenance> {
        if (fields.isEmpty()) return this
        val now = currentEpochMilliseconds()
        return this +
            fields.associateWith { FieldProvenance(FieldSourceKind.ENRICHMENT, provider = provider, at = now) }
    }

    private companion object {
        const val YEAR_DIGITS = 4
    }
}
