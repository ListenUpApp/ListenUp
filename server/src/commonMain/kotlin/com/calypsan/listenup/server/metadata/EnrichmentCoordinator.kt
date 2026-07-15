package com.calypsan.listenup.server.metadata

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataDomain
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.metadata.spi.BookContributorMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreSource
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.BookIdentitySource
import com.calypsan.listenup.server.metadata.spi.BookMatch
import com.calypsan.listenup.server.metadata.spi.ChapterListMeta
import com.calypsan.listenup.server.metadata.spi.MatchScorer
import com.calypsan.listenup.server.metadata.spi.ChapterSource
import com.calypsan.listenup.server.metadata.spi.CharacterMeta
import com.calypsan.listenup.server.metadata.spi.CharacterSource
import com.calypsan.listenup.server.metadata.spi.ContributorHitMeta
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.metadata.spi.ContributorSource
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.CoverSource
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.GenreLadderSource
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
 * field can come from one catalog and its neighbor from another. [fieldProviders]
 * records which catalog actually won each field, so the apply layer can stamp honest
 * per-field provenance instead of crediting a single hardcoded provider. All fields
 * are empty-able: a total catalog miss yields `Success(null)` from
 * [EnrichmentCoordinator.composeBook] rather than a blank [ComposedBook].
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
    /** The winning provider per resolved field — the source that supplied that field's value. */
    val fieldProviders: Map<BookField, MetadataProviderId>,
)

/**
 * Composes a book's metadata across the registered providers, per the operator's
 * [EnrichmentRoutes].
 *
 * For each metadata domain it needs, the coordinator fans a lookup across the
 * registered providers that both implement the domain's capability *and* the operator
 * routed to that domain ([EnrichmentRoutes.providersFor]) — once each, in parallel,
 * failure-contained (promoting `CoverSearchService`'s pattern: a provider that errors
 * or throws is logged and skipped, never sinking the others; [CancellationException]
 * is always re-raised). It then resolves each field first-non-empty by walking that
 * field's chain from [EnrichmentRoutes.orderFor], so a lean catalog early in a chain
 * contributes what it has and the next fills the rest. Covers take the first non-blank
 * URL (and, separately, the first non-blank max-resolution URL); chapters prefer a
 * catalog-verified list, else the first non-empty one.
 *
 * Server-internal orchestration: it speaks neutral `*Meta` types only. A total catalog
 * miss is `Success(null)`; a run where every consulted core provider *errored* (a
 * likely outage) is a typed [MetadataError.ExternalUnavailable] rather than a silent
 * miss — honest over silent, the never-strand anchor for the caller.
 */
internal class EnrichmentCoordinator(
    private val registry: MetadataProviderRegistry,
    private val routes: EnrichmentRoutes,
) {
    /**
     * Composes the full book preview for [identity] in [locale] — core fields, cover,
     * genres, and series. Returns `Success(null)` when no provider has core metadata for the
     * book (a catalog miss), or [MetadataError.ExternalUnavailable] when every consulted core
     * provider errored (an outage, not an honest miss); [refresh] bypasses any provider-side
     * cache on the core fetch.
     */
    suspend fun composeBook(
        identity: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean = false,
    ): AppResult<ComposedBook?> =
        coroutineScope {
            val coreOutcomes =
                fanOutOutcomes(
                    registry.capable<BookCoreSource>(),
                    MetadataDomain.BOOK_CORE,
                    "book-core",
                ) { it.getBookCore(identity, locale, refresh) }
            val cores = coreOutcomes.succeededValues()
            if (cores.isEmpty()) {
                // Distinguish an outage (every consulted provider errored) from an honest miss: if even
                // one working provider said "not in my catalog", it's a genuine miss; only when every
                // consulted provider failed is it an outage worth surfacing as unavailable.
                val allFailed =
                    coreOutcomes.values.isNotEmpty() && coreOutcomes.values.all { it is ProviderOutcome.Failed }
                return@coroutineScope if (allFailed) {
                    AppResult.Failure(
                        MetadataError.ExternalUnavailable(
                            debugInfo = "all core metadata providers failed for asin=${identity.asin}",
                        ),
                    )
                } else {
                    AppResult.Success(null)
                }
            }

            val (core, coreWinners) = mergeCore(cores)
            // Cover search is title-keyed, so it needs the resolved core's title/author; genres and
            // series are ASIN-keyed and can fan out alongside it.
            val coverIdentity =
                identity.copy(
                    title = core.title ?: identity.title,
                    primaryAuthor = core.authors.firstOrNull()?.name ?: identity.primaryAuthor,
                )
            val covers =
                async {
                    fanOut(registry.capable<CoverSource>(), MetadataDomain.COVER, "cover") {
                        it.searchCovers(coverIdentity, locale)
                    }
                }
            val genres =
                async {
                    fanOut(registry.capable<GenreSource>(), MetadataDomain.GENRES, "genres") {
                        it.getGenres(identity, locale)
                    }
                }
            val series =
                async {
                    fanOut(registry.capable<SeriesSource>(), MetadataDomain.SERIES, "series") {
                        it.getSeries(identity, locale)
                    }
                }

            val coversByProvider = covers.await()
            val genresByProvider = genres.await()
            val seriesByProvider = series.await()
            AppResult.Success(
                ComposedBook(
                    asin = identity.asin,
                    core = core,
                    coverUrl = resolveCover(coversByProvider) { it.url },
                    coverUrlMaxSize = resolveCover(coversByProvider) { it.maxSizeUrl },
                    genres = resolveList(BookField.GENRES, genresByProvider),
                    series = resolveList(BookField.SERIES, seriesByProvider),
                    fieldProviders =
                        coreWinners +
                            listOfNotNull(
                                coverWinner(coversByProvider)?.let { BookField.COVER to it },
                                listWinner(BookField.GENRES, genresByProvider)?.let { BookField.GENRES to it },
                                listWinner(BookField.SERIES, seriesByProvider)?.let { BookField.SERIES to it },
                            ),
                ),
            )
        }

    /**
     * Composes the chapter list for [identity] in [locale], preferring a catalog-verified
     * ([ChapterListMeta.accurate]) list over a heuristic one, else the first non-empty list.
     * Returns `null` when no provider has chapters. [refresh] bypasses any provider-side cache
     * on the chapter fetch, so a stale (long-TTL) list can be forced fresh.
     */
    suspend fun composeChapters(
        identity: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean = false,
    ): ChapterListMeta? {
        val byProvider =
            fanOut(registry.capable<ChapterSource>(), MetadataDomain.CHAPTERS, "chapters") {
                it.getChapters(identity, locale, refresh)
            }
        val order = routes.orderFor(BookField.CHAPTERS)
        return order.firstNotNullOfOrNull { byProvider[it]?.takeIf { list -> list.accurate } }
            ?: order.firstNotNullOfOrNull { byProvider[it]?.takeIf { list -> list.chapters.isNotEmpty() } }
    }

    /**
     * Composes the root→leaf genre ladders for [identity] in [locale] — the first non-empty
     * set walking the GENRES provider order. Ladders drive the genre-hierarchy links on apply;
     * only a [GenreLadderSource] (Audible today) contributes, so a catalog with flat genres but
     * no hierarchy contributes nothing. Each source is failure-contained; a total miss is empty.
     */
    suspend fun composeGenreLadders(
        identity: BookIdentity,
        locale: MetadataLocale,
    ): List<List<String>> {
        val byProvider =
            fanOut(registry.capable<GenreLadderSource>(), MetadataDomain.GENRES, "genre-ladders") {
                it.getGenreLadders(identity, locale)
            }
        return resolveList(BookField.GENRES, byProvider)
    }

    /**
     * Composes the character list for [identity] in [locale] — the honest empty slot.
     *
     * No built-in provider implements [CharacterSource] (there is no public per-book
     * character catalog), so with the default routes this returns an empty list rather
     * than fabricating data — the user-facing story is manual character entry. When an
     * operator points a custom provider at a character source and routes
     * [MetadataDomain.CHARACTERS] to it, that provider slots straight in here: the method
     * fans out across every registered [CharacterSource] and returns the first non-empty
     * list walking the CHARACTERS provider order. Each source is failure-contained.
     */
    suspend fun composeCharacters(
        identity: BookIdentity,
        locale: MetadataLocale,
    ): List<CharacterMeta> {
        val byProvider =
            fanOut(registry.capable<CharacterSource>(), MetadataDomain.CHARACTERS, "characters") {
                it.getCharacters(identity, locale)
            }
        val order = routes.domainOrder.getValue(MetadataDomain.CHARACTERS)
        return order.firstNotNullOfOrNull { byProvider[it]?.takeIf { list -> list.isNotEmpty() } } ?: emptyList()
    }

    /**
     * Searches contributor profiles for [name] in [locale] across every registered
     * [ContributorSource], returning the first non-empty hit list walking the CONTRIBUTORS
     * provider order. Each source is failure-contained; a total miss yields an empty list.
     */
    suspend fun searchContributors(
        name: String,
        locale: MetadataLocale,
    ): List<ContributorHitMeta> {
        val byProvider =
            fanOut(registry.capable<ContributorSource>(), MetadataDomain.CONTRIBUTORS, "contributor-search") {
                it.searchContributors(name, locale).map { hits -> hits.ifEmpty { null } }
            }
        return contributorOrder().firstNotNullOfOrNull { byProvider[it] } ?: emptyList()
    }

    /**
     * Fetches the contributor profile for [key] in [locale], returning the first provider
     * with a profile walking the CONTRIBUTORS order (`null` when none has one). [refresh]
     * bypasses any provider-side cache. Each source is failure-contained.
     */
    suspend fun getContributor(
        key: String,
        locale: MetadataLocale,
        refresh: Boolean = false,
    ): ContributorMeta? {
        val byProvider =
            fanOut(registry.capable<ContributorSource>(), MetadataDomain.CONTRIBUTORS, "contributor-profile") {
                it.getContributor(key, locale, refresh)
            }
        return contributorOrder().firstNotNullOfOrNull { byProvider[it] }
    }

    /** The provider precedence for contributor profiles. */
    private fun contributorOrder(): List<MetadataProviderId> = routes.domainOrder.getValue(MetadataDomain.CONTRIBUTORS)

    /**
     * Searches every registered [BookIdentitySource] for [query] in [locale], aggregating
     * their ranked candidates. Sources are consulted in the configured book-core order;
     * each is failure-contained, so one catalog erroring never sinks the others.
     *
     * When [local] is supplied, the aggregated candidates are re-ranked by [MatchScorer]
     * against that book's title/author/runtime — the duration-weighted confidence that
     * replaces each source's provisional score. Without it (no local book context), the
     * sources' own relevance order is preserved.
     */
    suspend fun searchBooks(
        query: String,
        locale: MetadataLocale,
        local: BookIdentity? = null,
    ): List<BookMatch> {
        val candidates =
            coroutineScope {
                orderedIdentitySources()
                    .map { source ->
                        async { contained(source.id, "search") { source.searchBooks(query, locale) }.valueOrNull() }
                    }.awaitAll()
                    .filterNotNull()
                    .flatten()
            }
        return if (local != null) MatchScorer.rank(local, candidates) else candidates
    }

    /** The registered identity sources ranked by the book-core provider order (unlisted last). */
    private fun orderedIdentitySources(): List<BookIdentitySource> {
        val order = routes.domainOrder.getValue(MetadataDomain.BOOK_CORE)
        return registry.capable<BookIdentitySource>().sortedBy { source ->
            order.indexOf(source.id).let { if (it < 0) Int.MAX_VALUE else it }
        }
    }

    /** Merges each core field first-non-empty across its chain, recording the winning provider per field. */
    private fun mergeCore(
        cores: Map<MetadataProviderId, BookCoreMeta>,
    ): Pair<BookCoreMeta, Map<BookField, MetadataProviderId>> {
        val winners = mutableMapOf<BookField, MetadataProviderId>()

        fun str(
            field: BookField,
            select: (BookCoreMeta) -> String?,
        ): String? {
            val id = routes.orderFor(field).firstOrNull { cores[it]?.let(select)?.isNotBlank() == true }
            id?.let { winners[field] = it }
            return id?.let { cores.getValue(it).let(select) }
        }

        fun credits(
            field: BookField,
            select: (BookCoreMeta) -> List<BookContributorMeta>,
        ): List<BookContributorMeta> {
            val id = routes.orderFor(field).firstOrNull { cores[it]?.let(select)?.isNotEmpty() == true }
            id?.let { winners[field] = it }
            return id?.let { cores.getValue(it).let(select) } ?: emptyList()
        }

        val coreOrder = routes.domainOrder.getValue(MetadataDomain.BOOK_CORE)
        val meta =
            BookCoreMeta(
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
        return meta to winners
    }

    /** The first non-blank cover URL (selected by [pick]) walking the cover chain. */
    private fun resolveCover(
        covers: Map<MetadataProviderId, List<CoverMeta>>,
        pick: (CoverMeta) -> String?,
    ): String? =
        routes.orderFor(BookField.COVER).firstNotNullOfOrNull { id ->
            covers[id]?.firstNotNullOfOrNull { pick(it)?.takeIf(String::isNotBlank) }
        }

    /** The provider whose covers supply the first non-blank primary URL walking the cover chain. */
    private fun coverWinner(covers: Map<MetadataProviderId, List<CoverMeta>>): MetadataProviderId? =
        routes.orderFor(BookField.COVER).firstOrNull { id ->
            covers[id]?.any { it.url.isNotBlank() } == true
        }

    /** The first non-empty list walking [field]'s chain, else empty. */
    private fun <T> resolveList(
        field: BookField,
        byProvider: Map<MetadataProviderId, List<T>>,
    ): List<T> =
        routes.orderFor(field).firstNotNullOfOrNull { byProvider[it]?.takeIf { list -> list.isNotEmpty() } }
            ?: emptyList()

    /** The provider whose list wins [field] (first non-empty walking the chain), or `null`. */
    private fun <T> listWinner(
        field: BookField,
        byProvider: Map<MetadataProviderId, List<T>>,
    ): MetadataProviderId? = routes.orderFor(field).firstOrNull { byProvider[it]?.isNotEmpty() == true }

    /**
     * Fetches [block] from every routed [providers] entry once, in parallel and contained, keyed
     * by id — keeping only the providers the operator routed to [domain]. Returns the succeeded,
     * non-empty values; failures and honest misses drop out.
     */
    private suspend fun <C : MetadataCapability, T : Any> fanOut(
        providers: List<C>,
        domain: MetadataDomain,
        label: String,
        block: suspend (C) -> AppResult<T?>,
    ): Map<MetadataProviderId, T> = fanOutOutcomes(providers, domain, label, block).succeededValues()

    /**
     * Like [fanOut] but preserves each routed provider's [ProviderOutcome] so a caller can tell a
     * real failure (outage) apart from an honest empty — the distinction [composeBook] needs to
     * return [MetadataError.ExternalUnavailable] instead of a silent miss.
     */
    private suspend fun <C : MetadataCapability, T : Any> fanOutOutcomes(
        providers: List<C>,
        domain: MetadataDomain,
        label: String,
        block: suspend (C) -> AppResult<T?>,
    ): Map<MetadataProviderId, ProviderOutcome<T>> {
        val allowed = routes.providersFor(domain)
        return coroutineScope {
            providers
                .filter { it.id in allowed }
                .map { provider -> async { provider.id to contained(provider.id, label) { block(provider) } } }
                .awaitAll()
                .toMap()
        }
    }

    /** The succeeded, non-empty values from an outcome map — failures and honest misses drop out. */
    private fun <T> Map<MetadataProviderId, ProviderOutcome<T>>.succeededValues(): Map<MetadataProviderId, T> =
        mapNotNull { (id, outcome) -> if (outcome is ProviderOutcome.Value) id to outcome.value else null }.toMap()

    /** The provider's value if it succeeded with data, else `null` (honest miss or failure). */
    private fun <T> ProviderOutcome<T>.valueOrNull(): T? = if (this is ProviderOutcome.Value) value else null

    /**
     * Runs [block], classifying the result: a value (present), an honest empty ([AppResult.Success]
     * of `null`), or a failure (typed [AppResult.Failure] or a thrown fault — both logged, cancellation
     * re-raised). Keeping "failed" distinct from "empty" is what lets [composeBook] surface an outage.
     */
    private suspend fun <T> contained(
        id: MetadataProviderId,
        label: String,
        block: suspend () -> AppResult<T?>,
    ): ProviderOutcome<T> =
        try {
            when (val result = block()) {
                is AppResult.Success -> {
                    result.data?.let { ProviderOutcome.Value(it) } ?: ProviderOutcome.Empty
                }

                is AppResult.Failure -> {
                    logger.warn { "enrichment: $label from ${id.value} failed (${result.error.code}) — skipping" }
                    ProviderOutcome.Failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "enrichment: $label from ${id.value} threw — skipping" }
            ProviderOutcome.Failed
        }

    /** The classified result of one contained provider call — value, honest empty, or failure. */
    private sealed interface ProviderOutcome<out T> {
        /** The provider returned data. */
        data class Value<T>(
            val value: T,
        ) : ProviderOutcome<T>

        /** The provider succeeded but has nothing for this book — a normal miss. */
        data object Empty : ProviderOutcome<Nothing>

        /** The provider errored or threw — a real failure, not a miss. */
        data object Failed : ProviderOutcome<Nothing>
    }
}
