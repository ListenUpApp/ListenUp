package com.calypsan.listenup.server.metadata

import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataDomain
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.metadata.spi.BookContributorMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreSource
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.BookIdentitySource
import com.calypsan.listenup.server.metadata.spi.BookMatch
import com.calypsan.listenup.server.metadata.spi.ChapterListMeta
import com.calypsan.listenup.server.metadata.spi.ChapterSource
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.CoverSource
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.GenreSource
import com.calypsan.listenup.server.metadata.spi.MetadataCapability
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.metadata.spi.SeriesSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private val logger = loggerFor<EnrichmentCoordinator>()

/**
 * A book's metadata composed across the provider registry per the operator's
 * [EnrichmentRoutes] — the neutral result the coordinator hands back before it is
 * mapped to a wire DTO.
 *
 * Every slot is resolved first-non-empty across each domain's provider chain, so a
 * field can come from one catalog and its neighbor from another. All fields are
 * empty-able: a total catalog miss yields `null` from [EnrichmentCoordinator.composeBook]
 * rather than a blank [ComposedBook].
 */
internal data class ComposedBook(
    /** The catalog key the compose ran for, echoed from the lookup [BookIdentity]. */
    val asin: String?,
    /** The merged core fields (title, description, credits, …), resolved per field. */
    val core: BookCoreMeta,
    /** The first non-blank cover URL walking the cover chain, or `null` when none. */
    val coverUrl: String?,
    /** The first non-blank max-resolution cover URL walking the cover chain, or `null`. */
    val coverUrlMaxSize: String?,
    /** The first non-empty genre list walking the genre chain. */
    val genres: List<GenreMeta>,
    /** The first non-empty series list walking the series chain. */
    val series: List<SeriesMeta>,
)

/**
 * Composes a book's metadata across the registered providers, per the operator's
 * [EnrichmentRoutes].
 *
 * For each metadata domain it needs, the coordinator fans a lookup across every
 * registered provider that implements the domain's capability — once each, in
 * parallel, failure-contained (promoting `CoverSearchService`'s pattern: a provider
 * that errors or throws is logged and skipped, never sinking the others;
 * [CancellationException] is always re-raised). It then resolves each field
 * first-non-empty by walking that field's chain from [EnrichmentRoutes.orderFor],
 * so a lean catalog early in a chain contributes what it has and the next fills the
 * rest. Covers take the first non-blank URL (and, separately, the first non-blank
 * max-resolution URL); chapters prefer a catalog-verified list, else the first
 * non-empty one.
 *
 * Server-internal orchestration: it speaks neutral `*Meta` types only and never
 * fails outward — a total catalog miss is a `null` [ComposedBook], the never-strand
 * anchor for the caller.
 */
internal class EnrichmentCoordinator(
    private val registry: MetadataProviderRegistry,
    private val routes: EnrichmentRoutes,
) {
    /**
     * Composes the full book preview for [identity] in [locale] — core fields, cover,
     * genres, and series. Returns `null` when no provider has core metadata for the book
     * (a catalog miss); [refresh] bypasses any provider-side cache on the core fetch.
     */
    suspend fun composeBook(
        identity: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean = false,
    ): ComposedBook? =
        coroutineScope {
            val cores =
                fanOut(registry.capable<BookCoreSource>(), "book-core") { it.getBookCore(identity, locale, refresh) }
            if (cores.isEmpty()) return@coroutineScope null

            val core = mergeCore(cores)
            // Cover search is title-keyed, so it needs the resolved core's title/author; genres and
            // series are ASIN-keyed and can fan out alongside it.
            val coverIdentity =
                identity.copy(
                    title = core.title ?: identity.title,
                    primaryAuthor = core.authors.firstOrNull()?.name ?: identity.primaryAuthor,
                )
            val covers =
                async { fanOut(registry.capable<CoverSource>(), "cover") { it.searchCovers(coverIdentity, locale) } }
            val genres = async { fanOut(registry.capable<GenreSource>(), "genres") { it.getGenres(identity, locale) } }
            val series = async { fanOut(registry.capable<SeriesSource>(), "series") { it.getSeries(identity, locale) } }

            val coversByProvider = covers.await()
            ComposedBook(
                asin = identity.asin,
                core = core,
                coverUrl = resolveCover(coversByProvider) { it.url },
                coverUrlMaxSize = resolveCover(coversByProvider) { it.maxSizeUrl },
                genres = resolveList(BookField.GENRES, genres.await()),
                series = resolveList(BookField.SERIES, series.await()),
            )
        }

    /**
     * Composes the chapter list for [identity] in [locale], preferring a catalog-verified
     * ([ChapterListMeta.accurate]) list over a heuristic one, else the first non-empty list.
     * Returns `null` when no provider has chapters.
     */
    suspend fun composeChapters(
        identity: BookIdentity,
        locale: MetadataLocale,
    ): ChapterListMeta? {
        val byProvider = fanOut(registry.capable<ChapterSource>(), "chapters") { it.getChapters(identity, locale) }
        val order = routes.orderFor(BookField.CHAPTERS)
        return order.firstNotNullOfOrNull { byProvider[it]?.takeIf { list -> list.accurate } }
            ?: order.firstNotNullOfOrNull { byProvider[it]?.takeIf { list -> list.chapters.isNotEmpty() } }
    }

    /**
     * Searches every registered [BookIdentitySource] for [query] in [locale], aggregating
     * their ranked candidates. Sources are consulted in the configured book-core order;
     * each is failure-contained, so one catalog erroring never sinks the others.
     */
    suspend fun searchBooks(
        query: String,
        locale: MetadataLocale,
    ): List<BookMatch> =
        coroutineScope {
            orderedIdentitySources()
                .map { source -> async { contained(source.id, "search") { source.searchBooks(query, locale) } } }
                .awaitAll()
                .filterNotNull()
                .flatten()
        }

    /** The registered identity sources ranked by the book-core provider order (unlisted last). */
    private fun orderedIdentitySources(): List<BookIdentitySource> {
        val order = routes.domainOrder.getValue(MetadataDomain.BOOK_CORE)
        return registry.capable<BookIdentitySource>().sortedBy { source ->
            order.indexOf(source.id).let { if (it < 0) Int.MAX_VALUE else it }
        }
    }

    /** Merges each core field first-non-empty across its chain; unresolved fields stay `null`/empty. */
    private fun mergeCore(cores: Map<MetadataProviderId, BookCoreMeta>): BookCoreMeta {
        fun str(
            field: BookField,
            select: (BookCoreMeta) -> String?,
        ): String? =
            routes.orderFor(field).firstNotNullOfOrNull { id ->
                cores[id]?.let(select)?.takeIf(String::isNotBlank)
            }

        fun credits(
            field: BookField,
            select: (BookCoreMeta) -> List<BookContributorMeta>,
        ): List<BookContributorMeta> =
            routes.orderFor(field).firstNotNullOfOrNull { id -> cores[id]?.let(select)?.takeIf { it.isNotEmpty() } }
                ?: emptyList()

        val coreOrder = routes.domainOrder.getValue(MetadataDomain.BOOK_CORE)
        return BookCoreMeta(
            title = str(BookField.TITLE) { it.title },
            subtitle = str(BookField.SUBTITLE) { it.subtitle },
            description = str(BookField.DESCRIPTION) { it.description },
            publisher = str(BookField.PUBLISHER) { it.publisher },
            releaseDate = str(BookField.PUBLISH_YEAR) { it.releaseDate },
            language = str(BookField.LANGUAGE) { it.language },
            runtimeMinutes = coreOrder.firstNotNullOfOrNull { cores[it]?.runtimeMinutes?.takeIf { m -> m > 0 } },
            authors = credits(BookField.AUTHORS) { it.authors },
            narrators = credits(BookField.NARRATORS) { it.narrators },
        )
    }

    /** The first non-blank cover URL (selected by [pick]) walking the cover chain. */
    private fun resolveCover(
        covers: Map<MetadataProviderId, List<CoverMeta>>,
        pick: (CoverMeta) -> String?,
    ): String? =
        routes.orderFor(BookField.COVER).firstNotNullOfOrNull { id ->
            covers[id]?.firstNotNullOfOrNull { pick(it)?.takeIf(String::isNotBlank) }
        }

    /** The first non-empty list walking [field]'s chain, else empty. */
    private fun <T> resolveList(
        field: BookField,
        byProvider: Map<MetadataProviderId, List<T>>,
    ): List<T> =
        routes.orderFor(field).firstNotNullOfOrNull { byProvider[it]?.takeIf { list -> list.isNotEmpty() } }
            ?: emptyList()

    /** Fetches [block] from every [providers] entry once, in parallel and contained, keyed by id. */
    private suspend fun <C : MetadataCapability, T : Any> fanOut(
        providers: List<C>,
        label: String,
        block: suspend (C) -> AppResult<T?>,
    ): Map<MetadataProviderId, T> =
        coroutineScope {
            providers
                .map { provider -> async { provider.id to contained(provider.id, label) { block(provider) } } }
                .awaitAll()
                .mapNotNull { (id, value) -> value?.let { id to it } }
                .toMap()
        }

    /** Runs [block], turning a typed failure or a thrown fault into `null` (logged); re-raises cancellation. */
    private suspend fun <T> contained(
        id: MetadataProviderId,
        label: String,
        block: suspend () -> AppResult<T?>,
    ): T? =
        try {
            when (val result = block()) {
                is AppResult.Success -> {
                    result.data
                }

                is AppResult.Failure -> {
                    logger.warn { "enrichment: $label from ${id.value} failed (${result.error.code}) — skipping" }
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "enrichment: $label from ${id.value} threw — skipping" }
            null
        }
}
