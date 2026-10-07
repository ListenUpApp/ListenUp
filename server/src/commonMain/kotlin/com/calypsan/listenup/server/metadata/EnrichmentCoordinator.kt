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
import com.calypsan.listenup.server.metadata.spi.MoodSource
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.metadata.spi.SeriesSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

private val logger = loggerFor<EnrichmentCoordinator>()

/**
 * A book's metadata composed across the provider registry per the operator's
 * [EnrichmentRoutes] — the neutral result the coordinator hands back before it is
 * mapped to a wire DTO.
 *
 * Every slot is resolved first-non-empty across each domain's provider chain, except
 * genres, where a gap filler's are unioned after the winner's, so a field can come from
 * one catalog and its neighbor from another. [fieldProviders]
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
    /** The provider whose covers supply the applied max-size cover URL, or `null` when none. */
    val coverMaxSizeWinner: MetadataProviderId? = null,
    /** The first non-empty mood list walking the moods chain — Hardcover's, by default (#1542). */
    val moods: List<String> = emptyList(),
    /**
     * Genre name → the gap filler that added it beside the genres winner's own (#1542). Sparse; when a
     * gap filler is the genres winner itself, every genre is listed.
     */
    val genreProviders: Map<String, MetadataProviderId> = emptyMap(),
)

/** Why one provider's core fetch produced nothing usable. */
internal sealed interface CoreFailure {
    /** It errored or threw. */
    data object Failed : CoreFailure

    /** It didn't answer within the compose deadline. */
    data object TimedOut : CoreFailure

    /** It asked us to slow down, for [retryAfterSeconds] when it said. */
    data class RateLimited(
        val retryAfterSeconds: Long?,
    ) : CoreFailure
}

/**
 * Every provider's answer for one book, kept per provider ([EnrichmentCoordinator.composeOptions]). [coreAsked]
 * is every core provider the routes let it ask; [coreFailures] are the ones that failed, and why. The maps hold
 * only providers that answered with something.
 */
internal data class ComposedOptions(
    val cores: Map<MetadataProviderId, BookCoreMeta>,
    val coreAsked: Set<MetadataProviderId>,
    val coreFailures: Map<MetadataProviderId, CoreFailure>,
    val covers: Map<MetadataProviderId, List<CoverMeta>> = emptyMap(),
    val genres: Map<MetadataProviderId, List<GenreMeta>> = emptyMap(),
    val series: Map<MetadataProviderId, List<SeriesMeta>> = emptyMap(),
    val moods: Map<MetadataProviderId, List<String>> = emptyMap(),
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
    val routes: EnrichmentRoutes,
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
    ): AppResult<ComposedBook?> {
        val options =
            composeOptions(identity, locale, refresh) { asked, cores ->
                asked.any { it !in MetadataProviderId.gapFillers && it in cores }
            }
        // A gap filler (Hardcover) adds to a match; it can't make one. Whether the book was found, or the
        // catalogs are down, is decided by the providers that can identify a book. Distinguish an outage (every
        // one of those errored) from an honest miss (at least one said "not mine").
        val identifying = options.coreAsked.filter { it !in MetadataProviderId.gapFillers }
        if (identifying.none { it in options.cores }) {
            val allFailed = identifying.isNotEmpty() && identifying.all { it in options.coreFailures }
            return if (allFailed) {
                AppResult.Failure(
                    MetadataError.ExternalUnavailable(
                        debugInfo = "all core metadata providers failed for asin=${identity.asin}",
                    ),
                )
            } else {
                AppResult.Success(null)
            }
        }
        return AppResult.Success(firstOptions(identity, options))
    }

    /**
     * Composes [identity] in [locale] per provider (the matching redesign's Review): every routed provider's
     * core, covers, genres, series and moods are kept, never collapsed, so Review can offer each source's value.
     * Each provider is fetched by its own ref when [identity] carries one. Cover search is title-keyed, so it runs
     * after core, with the first core title and author. A provider that errors, throws or misses [deadline] is
     * recorded in [ComposedOptions.coreFailures] (for core) or simply absent; [CancellationException] is always
     * re-raised. [composeBook] is "take each field's first option" over this, so the two can't disagree.
     */
    suspend fun composeOptions(
        identity: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean = false,
        deadline: Duration? = null,
        proceed: (asked: Set<MetadataProviderId>, cores: Map<MetadataProviderId, BookCoreMeta>) -> Boolean =
            { _, _ -> true },
    ): ComposedOptions =
        coroutineScope {
            val coreOutcomes =
                fanOutOutcomes(
                    registry.capable<BookCoreSource>(),
                    MetadataDomain.BOOK_CORE,
                    "book-core",
                    deadline,
                ) { it.getBookCore(identity, locale, refresh) }
            val cores = coreOutcomes.succeededValues()
            val coreFailures =
                coreOutcomes
                    .mapNotNull { (id, outcome) -> (outcome as? ProviderOutcome.Failed)?.let { id to it.failure } }
                    .toMap()
            if (!proceed(coreOutcomes.keys, cores)) {
                return@coroutineScope ComposedOptions(
                    cores = cores,
                    coreAsked = coreOutcomes.keys,
                    coreFailures = coreFailures,
                )
            }
            val (core, _) = mergeCore(cores)
            val coverIdentity =
                identity.copy(
                    title = core.title ?: identity.title,
                    primaryAuthor = core.authors.firstOrNull()?.name ?: identity.primaryAuthor,
                )
            val covers =
                async {
                    fanOut(registry.capable<CoverSource>(), MetadataDomain.COVER, "cover", deadline) {
                        it.searchCovers(coverIdentity, locale)
                    }
                }
            val genres =
                async {
                    fanOut(registry.capable<GenreSource>(), MetadataDomain.GENRES, "genres", deadline) {
                        it.getGenres(identity, locale)
                    }
                }
            val series =
                async {
                    fanOut(registry.capable<SeriesSource>(), MetadataDomain.SERIES, "series", deadline) {
                        it.getSeries(identity, locale)
                    }
                }
            val moods =
                async {
                    fanOut(registry.capable<MoodSource>(), MetadataDomain.GENRES, "moods", deadline) {
                        it.getMoods(identity, locale)
                    }
                }
            ComposedOptions(
                cores = cores,
                coreAsked = coreOutcomes.keys,
                coreFailures = coreFailures,
                covers = covers.await(),
                genres = genres.await(),
                series = series.await(),
                moods = moods.await(),
            )
        }

    /** The legacy composition: each field's first option walking its chain, as [composeBook] always resolved it. */
    private fun firstOptions(
        identity: BookIdentity,
        options: ComposedOptions,
    ): ComposedBook {
        val (core, coreWinners) = mergeCore(options.cores)
        val genreUnion = unionGenres(options.genres)
        return ComposedBook(
            asin = identity.asin,
            core = core,
            coverUrl = resolveCover(options.covers) { it.url },
            coverUrlMaxSize = resolveCover(options.covers) { it.maxSizeUrl },
            genres = genreUnion.genres,
            series = resolveList(BookField.SERIES, options.series),
            fieldProviders =
                coreWinners +
                    listOfNotNull(
                        coverWinnerBy(options.covers) { it.url }?.let { BookField.COVER to it },
                        genreUnion.winner?.let { BookField.GENRES to it },
                        listWinner(BookField.SERIES, options.series)?.let { BookField.SERIES to it },
                        listWinner(BookField.MOODS, options.moods)?.let { BookField.MOODS to it },
                    ),
            coverMaxSizeWinner = coverWinnerBy(options.covers) { it.maxSizeUrl },
            moods = resolveList(BookField.MOODS, options.moods),
            genreProviders = genreUnion.addedBy,
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
    ): ChapterListMeta? = composeChaptersWithSource(identity, locale, refresh)?.second

    /** [composeChapters], with the provider whose list won — Review names it as the chapter names' source. */
    suspend fun composeChaptersWithSource(
        identity: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean = false,
    ): Pair<MetadataProviderId, ChapterListMeta>? {
        val byProvider =
            fanOut(registry.capable<ChapterSource>(), MetadataDomain.CHAPTERS, "chapters") {
                it.getChapters(identity, locale, refresh)
            }
        val order = routes.orderFor(BookField.CHAPTERS)
        val winner =
            order.firstOrNull { byProvider[it]?.accurate == true }
                ?: order.firstOrNull { byProvider[it]?.chapters?.isNotEmpty() == true }
                ?: return null
        return winner to byProvider.getValue(winner)
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
     * Fetches the contributor profile for [key] in [locale], returning the first provider with a profile
     * walking the CONTRIBUTORS order (`null` when none has one), its missing bio or photo filled from a gap
     * filler (#1542). [refresh] bypasses any provider-side cache. Each source is failure-contained.
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
        val profile = contributorOrder().firstNotNullOfOrNull { byProvider[it] } ?: return null
        val complete = !profile.description.isNullOrBlank() && !profile.imageUrl.isNullOrBlank()
        return if (complete) profile else fillProfileGaps(profile, locale)
    }

    /**
     * Fills [profile]'s blank bio or photo from a gap filler routed to contributors, which finds the person
     * by their exact name. Only blanks are filled; nothing the winner said is replaced.
     */
    private suspend fun fillProfileGaps(
        profile: ContributorMeta,
        locale: MetadataLocale,
    ): ContributorMeta {
        val routed = contributorOrder()
        val fillers =
            registry.capable<ContributorSource>().filter {
                it.id in MetadataProviderId.gapFillers &&
                    it.id in routed
            }
        for (filler in fillers) {
            val hit =
                contained(filler.id, "contributor-fill") {
                    filler.searchContributors(profile.name, locale).map { hits ->
                        hits.firstOrNull { it.name.equals(profile.name, ignoreCase = true) }
                    }
                }.valueOrNull() ?: continue
            if (hit.key == profile.key) continue
            val found =
                contained(filler.id, "contributor-fill") { filler.getContributor(hit.key, locale) }.valueOrNull()
                    ?: continue
            return profile.copy(
                description = profile.description?.takeIf { it.isNotBlank() } ?: found.description,
                imageUrl = profile.imageUrl?.takeIf { it.isNotBlank() } ?: found.imageUrl,
            )
        }
        return profile
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

    /** The provider whose covers supply the first non-blank URL (selected by [pick]) walking the cover chain. */
    private fun coverWinnerBy(
        covers: Map<MetadataProviderId, List<CoverMeta>>,
        pick: (CoverMeta) -> String?,
    ): MetadataProviderId? =
        routes.orderFor(BookField.COVER).firstOrNull { id ->
            covers[id]?.any { pick(it)?.isNotBlank() == true } == true
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

    /** Genres as the coordinator composes them: the winner's list, a gap filler's additions, and who added which. */
    private data class GenreUnion(
        val genres: List<GenreMeta>,
        val winner: MetadataProviderId?,
        val addedBy: Map<String, MetadataProviderId>,
    )

    /**
     * The genres winner is the first identifying provider with genres walking the chain (a gap filler only
     * when none has any). Each gap filler in the chain then adds the genres the winner lacks
     * (case-insensitively), and every added genre is recorded with the provider that added it (#1542).
     */
    private fun unionGenres(byProvider: Map<MetadataProviderId, List<GenreMeta>>): GenreUnion {
        val chain = routes.orderFor(BookField.GENRES)
        val winner =
            chain.firstOrNull { it !in MetadataProviderId.gapFillers && byProvider[it]?.isNotEmpty() == true }
                ?: chain.firstOrNull { byProvider[it]?.isNotEmpty() == true }
                ?: return GenreUnion(emptyList(), null, emptyMap())
        val genres = byProvider.getValue(winner).toMutableList()
        val seen = genres.mapTo(mutableSetOf()) { it.name.trim().lowercase() }
        val addedBy = mutableMapOf<String, MetadataProviderId>()
        if (winner in MetadataProviderId.gapFillers) genres.forEach { addedBy[it.name] = winner }
        chain
            .filter { it in MetadataProviderId.gapFillers && it != winner }
            .forEach { filler ->
                byProvider[filler].orEmpty().forEach { genre ->
                    if (seen.add(genre.name.trim().lowercase())) {
                        genres += genre
                        addedBy[genre.name] = filler
                    }
                }
            }
        return GenreUnion(genres, winner, addedBy)
    }

    /**
     * Fetches [block] from every routed [providers] entry once, in parallel and contained, keyed
     * by id — keeping only the providers the operator routed to [domain]. Returns the succeeded,
     * non-empty values; failures and honest misses drop out.
     */
    private suspend fun <C : MetadataCapability, T : Any> fanOut(
        providers: List<C>,
        domain: MetadataDomain,
        label: String,
        deadline: Duration? = null,
        block: suspend (C) -> AppResult<T?>,
    ): Map<MetadataProviderId, T> = fanOutOutcomes(providers, domain, label, deadline, block).succeededValues()

    /**
     * Like [fanOut] but preserves each routed provider's [ProviderOutcome] so a caller can tell a
     * real failure (outage) apart from an honest empty — the distinction [composeBook] needs to
     * return [MetadataError.ExternalUnavailable] instead of a silent miss.
     */
    private suspend fun <C : MetadataCapability, T : Any> fanOutOutcomes(
        providers: List<C>,
        domain: MetadataDomain,
        label: String,
        deadline: Duration? = null,
        block: suspend (C) -> AppResult<T?>,
    ): Map<MetadataProviderId, ProviderOutcome<T>> {
        val allowed = routes.providersFor(domain)
        return coroutineScope {
            providers
                .filter { it.id in allowed }
                .map { provider ->
                    async {
                        val outcome =
                            if (deadline == null) {
                                contained(provider.id, label) { block(provider) }
                            } else {
                                withTimeoutOrNull(deadline) { contained(provider.id, label) { block(provider) } }
                                    ?: ProviderOutcome.Failed(CoreFailure.TimedOut).also {
                                        logger.warn { "enrichment: $label from ${provider.id.value} timed out" }
                                    }
                            }
                        provider.id to outcome
                    }
                }.awaitAll()
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
                    val error = result.error
                    ProviderOutcome.Failed(
                        if (error is MetadataError.ExternalRateLimited) {
                            CoreFailure.RateLimited(error.retryAfterSeconds)
                        } else {
                            CoreFailure.Failed
                        },
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "enrichment: $label from ${id.value} threw — skipping" }
            ProviderOutcome.Failed(CoreFailure.Failed)
        }

    /** The classified result of one contained provider call — value, honest empty, or failure. */
    private sealed interface ProviderOutcome<out T> {
        /** The provider returned data. */
        data class Value<T>(
            val value: T,
        ) : ProviderOutcome<T>

        /** The provider succeeded but has nothing for this book — a normal miss. */
        data object Empty : ProviderOutcome<Nothing>

        /** The provider errored, threw or timed out — a real failure, not a miss. */
        data class Failed(
            val failure: CoreFailure,
        ) : ProviderOutcome<Nothing>
    }
}
