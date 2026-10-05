package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.dto.match.FindStrategy
import com.calypsan.listenup.api.dto.match.IdentifierKind
import com.calypsan.listenup.api.dto.match.RegionContext
import com.calypsan.listenup.api.dto.match.SearchStep
import com.calypsan.listenup.api.dto.match.SourceStatus
import com.calypsan.listenup.api.dto.match.UnavailableReason
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataDomain
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.metadata.spi.BookFindSource
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.FindAnswer
import com.calypsan.listenup.server.metadata.spi.FindAvailability
import com.calypsan.listenup.server.metadata.spi.FindLookup
import com.calypsan.listenup.server.metadata.spi.FindRole
import com.calypsan.listenup.server.metadata.spi.FindStep
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.RegionalSource
import com.calypsan.listenup.server.metadata.spi.toMetadataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = loggerFor<BookFinder>()

/** Each source gets this long to answer a Find; a slower one becomes "didn't answer in time" (spec). */
internal val FIND_DEADLINE: Duration = 8.seconds

/** The wait reported for a rate limit the source didn't time — the canvas's countdown. */
internal const val DEFAULT_RETRY_AFTER_SECONDS: Long = 30

/**
 * Find (spec, *Server → Find (books)*). Asks every routed Find source in parallel — identifying sources routed
 * to `BOOK_CORE`, then attaching ones routed to `COVER` — each under its own [deadline] and failure containment
 * ([CancellationException] is always re-raised). The hits are merged and ranked against the book, and each
 * source's outcome becomes one [SourceStatus]. Each source's full answer is kept in [cache], so a retry re-asks
 * only the sources that didn't answer.
 */
internal class BookFinder(
    private val registry: MetadataProviderRegistry,
    private val routes: EnrichmentRoutes,
    private val cache: FindCache = FindCache(),
    private val deadline: Duration = FIND_DEADLINE,
) {
    suspend fun find(
        subject: FindSubject,
        request: BookFindRequest,
        region: ResolvedRegion,
    ): BookFindResult {
        val query = request.query?.trim()?.takeIf { it.isNotEmpty() }
        val identify = request.strategy == FindStrategy.AUTOMATIC
        val text = query ?: subject.searchText()
        val identifying = routed(FindRole.IDENTIFIES, MetadataDomain.BOOK_CORE)
        val attaching = routed(FindRole.ATTACHES, MetadataDomain.COVER)
        val asked =
            coroutineScope {
                (identifying + attaching)
                    .map { source ->
                        async { Asked(source, ask(source, subject.lookupFor(source.id, identify, text), region.locale)) }
                    }.awaitAll()
            }
        val (found, covers) = asked.partition { it.source.findRole == FindRole.IDENTIFIES }
        val merged = MatchMerger.attach(MatchMerger.merge(found.flatMap { it.hits() }), covers.flatMap { it.hits() })
        return BookFindResult(
            yourCopy = subject.yourCopy(),
            steps = steps(found, query),
            candidates = CandidateRanker.rank(subject, region.locale.region, merged),
            sources = asked.map { it.status(subject, region.locale) },
            region = regionContext(identifying, region),
        )
    }

    /** Every Find source with [role] the operator routed to [domain], in that domain's order. */
    private fun routed(
        role: FindRole,
        domain: MetadataDomain,
    ): List<BookFindSource> {
        val order = routes.domainOrder.getValue(domain)
        val allowed = routes.providersFor(domain)
        return registry
            .capable<BookFindSource>()
            .filter { it.findRole == role && it.id in allowed }
            .sortedBy { source -> order.indexOf(source.id).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    /**
     * One source's outcome, within [deadline]. Availability is checked before the cache, so a source switched
     * off drops out at once; only a full answer is cached.
     */
    private suspend fun ask(
        source: BookFindSource,
        lookup: FindLookup,
        locale: MetadataLocale,
    ): Outcome =
        withTimeoutOrNull(deadline) {
            contained(source) {
                when (val availability = source.findAvailability()) {
                    is FindAvailability.Unavailable -> {
                        Outcome.Unavailable(availability.reason)
                    }

                    FindAvailability.Available -> {
                        val key = FindCache.Key(source.id, lookup, locale.region.takeIf { source is RegionalSource })
                        cache.get(key)?.let { Outcome.Answered(it) }
                            ?: source.findBooks(lookup, locale).toOutcome(source).also { outcome ->
                                if (outcome is Outcome.Answered) cache.put(key, outcome.answer)
                            }
                    }
                }
            }
        } ?: Outcome.TimedOut.also { logger.warn { "find: ${source.id} didn't answer within $deadline" } }

    private suspend fun contained(
        source: BookFindSource,
        block: suspend () -> Outcome,
    ): Outcome =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "find: ${source.id} threw — reported as failed" }
            Outcome.Failed
        }

    private fun AppResult<FindAnswer>.toOutcome(source: BookFindSource): Outcome =
        when (this) {
            is AppResult.Success -> {
                Outcome.Answered(data)
            }

            is AppResult.Failure -> {
                val failure = error
                if (failure is MetadataError.ExternalRateLimited) {
                    Outcome.RateLimited(failure.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS)
                } else {
                    logger.warn { "find: ${source.id} failed (${failure.code}) — reported as failed" }
                    Outcome.Failed
                }
            }
        }

    /** The steps Find took: each source's link, then identifiers, then the text search, which always runs. */
    private fun steps(
        found: List<Asked>,
        query: String?,
    ): List<SearchStep> {
        val ran = found.mapNotNull { asked -> (asked.outcome as? Outcome.Answered)?.let { asked.source to it.answer.steps } }
        return buildList {
            ran.filter { FindStep.LINK in it.second }.forEach { add(SearchStep.ExistingLink(it.first.id.toMetadataSource())) }
            if (ran.any { FindStep.ASIN in it.second }) add(SearchStep.Identifier(IdentifierKind.ASIN))
            if (ran.any { FindStep.ISBN in it.second }) add(SearchStep.Identifier(IdentifierKind.ISBN))
            add(query?.let { SearchStep.YourQuery(it) } ?: SearchStep.TitleAuthorLength)
        }.distinct()
    }

    /** The store searched, at the first routed source with stores; null when none has stores. */
    private fun regionContext(
        identifying: List<BookFindSource>,
        region: ResolvedRegion,
    ): RegionContext? {
        val regional = identifying.filterIsInstance<RegionalSource>().firstOrNull() ?: return null
        return RegionContext(
            source = regional.id.toMetadataSource(),
            region = region.locale,
            origin = region.origin,
            choices = MetadataLocale.SUPPORTED.filter { regional.hasStore(it.region) },
        )
    }

    /** How one source fared, as the wire says it; a source with stores that answered empty is "not found in this store". */
    private fun Asked.status(
        subject: FindSubject,
        locale: MetadataLocale,
    ): SourceStatus {
        val shown = source.id.toMetadataSource()
        return when (val outcome = outcome) {
            is Outcome.Answered -> {
                if (outcome.answer.books.isEmpty() && source is RegionalSource) {
                    SourceStatus.NotFoundInStore(shown, locale, suggestStores(locale, subject, source.id.value))
                } else {
                    SourceStatus.Answered(shown, outcome.answer.books.size)
                }
            }

            Outcome.TimedOut -> {
                SourceStatus.TimedOut(shown)
            }

            is Outcome.RateLimited -> {
                SourceStatus.RateLimited(shown, outcome.retryAfterSeconds)
            }

            Outcome.Failed -> {
                SourceStatus.Failed(shown)
            }

            is Outcome.Unavailable -> {
                SourceStatus.Unavailable(shown, outcome.reason)
            }
        }
    }

    /** A source, and how it fared. */
    private class Asked(
        val source: BookFindSource,
        val outcome: Outcome,
    ) {
        fun hits(): List<SourcedHit> =
            (outcome as? Outcome.Answered)
                ?.answer
                ?.books
                ?.map { SourcedHit(source.id, it) }
                .orEmpty()
    }

    /** How one source fared in one Find. */
    private sealed interface Outcome {
        data class Answered(
            val answer: FindAnswer,
        ) : Outcome

        data object TimedOut : Outcome

        data class RateLimited(
            val retryAfterSeconds: Long,
        ) : Outcome

        data object Failed : Outcome

        data class Unavailable(
            val reason: UnavailableReason,
        ) : Outcome
    }
}
