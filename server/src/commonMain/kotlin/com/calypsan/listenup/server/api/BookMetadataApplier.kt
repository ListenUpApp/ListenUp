package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.matching.apply.BookMatchWriter
import com.calypsan.listenup.server.matching.apply.LegacyMatchPlanner
import com.calypsan.listenup.server.matching.apply.MatchCatalogs
import com.calypsan.listenup.server.matching.apply.MatchCoverFiles
import com.calypsan.listenup.server.matching.apply.MatchPreparer
import com.calypsan.listenup.server.matching.undo.MatchReceiptStore
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreHierarchyFromLadder
import com.calypsan.listenup.server.services.SeriesRepository
import kotlinx.coroutines.CancellationException

private val log = loggerFor<BookMetadataApplier>()

/**
 * A composed metadata match ready to apply: the provider-neutral wire [book] plus the
 * per-field [fieldProviders] map recording which catalog actually won each field.
 *
 * The apply layer stays source-agnostic (it consumes the wire [MetadataBook], never a
 * catalog-internal type), while [fieldProviders] lets it stamp honest per-field
 * provenance — the field whose winning value came from iTunes records `itunes`, not a
 * blanket `audible`.
 */
internal data class MetadataMatch(
    /** The composed match projected onto the wire DTO. */
    val book: MetadataBook,
    /** The winning provider per resolved field, from the enrichment compose. */
    val fieldProviders: Map<BookField, MetadataProviderId>,
)

/**
 * The legacy `applyBookMetadata`: applies a chosen match to an existing book, honouring a per-field
 * [MetadataApplySelection], for the older clients that still call it.
 *
 * Its contract is what it always was (see [LegacyMatchPlanner]): ticked fields are written; a role is replaced
 * only when the selection resolves at least one of the match's credits; series when one is chosen; genres and
 * moods become exactly the selection; the ASIN is always stamped; a ticked cover that can't be fetched is
 * skipped. What changed (matching redesign PR 3): it runs through the same preparation and one-transaction
 * writer as Match details, so it is all-or-nothing and leaves a receipt that can be undone; and it no longer
 * writes tags.
 *
 * Returns [MetadataError.NotFound] when the book is absent or the provider has no match.
 */
internal class BookMetadataApplier(
    private val bookRepository: BookRepository,
    private val contributorRepository: ContributorRepository,
    private val seriesRepository: SeriesRepository,
    private val imageStorage: ImageStorage,
    private val coverImageStore: CoverImageStore,
    private val matchSource: suspend (asin: String, locale: MetadataLocale) -> AppResult<MetadataMatch?>,
    private val genreHierarchy: GenreHierarchyFromLadder,
    private val sqlDb: ListenUpDatabase,
    private val ladderSource: suspend (locale: MetadataLocale, asin: String) -> List<List<String>>,
    private val enrichmentDeps: MetadataEnrichmentDeps,
    /** Who applies the match — recorded on its receipt. */
    private val appliedBy: String,
    /**
     * Best-effort outside-ratings refresh, run right after a successful apply — the "on match"
     * trigger `ExternalRatingsFetcher` names. Null in every direct construction (this class's own
     * unit tests) and wherever the feature is unwired; production wires it to
     * `ExternalRatingsFetcher.fetch(bookId, locale, refresh = true)`, its return value discarded.
     */
    private val externalRatingsFetch: (suspend (bookId: BookId, locale: MetadataLocale) -> Unit)? = null,
) {
    suspend fun apply(
        bookId: BookId,
        asin: String,
        locale: MetadataLocale,
        selection: MetadataApplySelection,
    ): AppResult<Unit> {
        val existing =
            bookRepository.findById(bookId)
                ?: return AppResult.Failure(
                    MetadataError.NotFound(debugInfo = "Book ${bookId.value} not found in the database."),
                )
        val matched =
            when (val found = matchSource(asin, locale)) {
                is AppResult.Success -> found.data
                is AppResult.Failure -> return found
            } ?: return AppResult.Failure(
                MetadataError.NotFound(debugInfo = "No metadata for ASIN $asin in region ${locale.region}."),
            )
        val moods = enrichmentDeps.bookMoodWriter.currentMoods(bookId)
        val draft =
            LegacyMatchPlanner.plan(
                book = existing,
                currentMoods = moods.map { it.name },
                match = matched.book,
                fieldProviders = matched.fieldProviders,
                asin = asin,
                selection = selection,
                ladders = if (selection.genres.isEmpty()) emptyList() else laddersBestEffort(locale, asin),
            )
        val plan =
            when (val prepared = preparer().prepare(draft, existing, moods)) {
                is AppResult.Success -> prepared.data
                is AppResult.Failure -> return prepared
            }
        val written = writer().write(plan, basedOnRevision = null, appliedBy = appliedBy).map { }
        if (written is AppResult.Success) applyExternalRatingsBestEffort(bookId, locale)
        return written
    }

    private fun preparer() =
        MatchPreparer(
            catalogs =
                MatchCatalogs(
                    // resolveOrCreate derives sort name internally; passing null matches any scanner row for the
                    // same person, converging all creation paths on one dedup bucket.
                    contributorId = { contributorRepository.resolveOrCreate(it, sortName = null).value },
                    seriesId = { seriesRepository.resolveOrCreate(it).value },
                    genreIds = { bookRepository.resolveGenreIds(it) },
                    ladderRungs = { genreHierarchy.ensureLadder(it) },
                    moodId = { enrichmentDeps.bookMoodWriter.resolveMoodId(it) },
                ),
            covers = MatchCoverFiles(imageStorage, coverImageStore),
            now = ::currentEpochMilliseconds,
        )

    private fun writer() =
        BookMatchWriter(
            db = sqlDb,
            books = bookRepository,
            moods = enrichmentDeps.bookMoodWriter.bookMoodRepository,
            receipts = MatchReceiptStore(sqlDb),
            now = ::currentEpochMilliseconds,
        )

    /** Audible's category ladders, so a parent genre surfaces the book; a failed fetch just links none. */
    private suspend fun laddersBestEffort(
        locale: MetadataLocale,
        asin: String,
    ): List<List<String>> =
        try {
            ladderSource(locale, asin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Genre ladders unavailable for ASIN $asin — applying without hierarchy" }
            emptyList()
        }

    /**
     * Runs [externalRatingsFetch] for [bookId] in [locale] right after a successful apply — the book
     * just gained (or changed) an ASIN, so this is the earliest point a fresh outside rating can be
     * fetched. Best-effort: the match itself already committed, so a thrown fault here must never fail
     * the apply. [CancellationException] is always re-raised.
     */
    private suspend fun applyExternalRatingsBestEffort(
        bookId: BookId,
        locale: MetadataLocale,
    ) {
        val fetch = externalRatingsFetch ?: return
        try {
            fetch(bookId, locale)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "External rating fetch failed for ${bookId.value} after apply — skipping" }
        }
    }
}
