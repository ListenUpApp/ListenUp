package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.match.InLibrary
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.PersonFindResult
import com.calypsan.listenup.api.dto.match.PersonSearchStep
import com.calypsan.listenup.api.dto.match.RoleCoverage
import com.calypsan.listenup.api.metadata.MetadataDomain
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.PersonAnswer
import com.calypsan.listenup.server.metadata.spi.PersonFindSource
import com.calypsan.listenup.server.metadata.spi.PersonStep
import com.calypsan.listenup.server.metadata.spi.presentedAs
import com.calypsan.listenup.server.metadata.spi.toMetadataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.Duration

/** How many of the person's book titles a people Find echoes. */
private const val IN_LIBRARY_TITLES = 3

/**
 * People Find (spec, *Find and Review for people*). Asks every people source routed to `CONTRIBUTORS`, in
 * parallel, each under its own [deadline] and failure containment — whatever roles brought the person into the
 * library, since a role on one book doesn't say who someone is. The people found are merged conservatively and
 * ranked by the subject's own books. Each source's full answer is kept in [cache], so Retry re-asks only the
 * sources that didn't answer. [PersonFindRequest.role], sent by older clients, is only echoed.
 */
internal class PeopleFinder(
    private val registry: MetadataProviderRegistry,
    private val routes: EnrichmentRoutes,
    private val cache: PeopleFindCache = PeopleFindCache(),
    private val deadline: Duration = FIND_DEADLINE,
) {
    suspend fun find(
        subject: PeopleSubject,
        request: PersonFindRequest,
        locale: MetadataLocale,
    ): PersonFindResult {
        val query = request.query?.run { trim().takeIf { it.isNotEmpty() } }
        val sources = routed()
        val asked =
            coroutineScope {
                sources
                    .map { source ->
                        async { source to ask(source = source, subject = subject, query = query, locale = locale) }
                    }.awaitAll()
            }
        val found =
            asked.flatMap { (source, outcome) ->
                (outcome as? SourceOutcome.Answered)
                    ?.run { answer.people }
                    .orEmpty()
                    .map { SourcedPerson(source.id.presentedAs(), it) }
            }
        return PersonFindResult(
            role = request.role,
            steps = steps(asked, subject, query),
            inLibrary = InLibrary(subject.books.size, subject.books.take(IN_LIBRARY_TITLES).map { it.title }),
            coverage = sources.map { RoleCoverage(it.id.toMetadataSource(), hasProfiles = true) },
            candidates = PeopleRanker.rank(subject, PeopleMerger.merge(found)),
            sources =
                asked.map { (source, outcome) ->
                    outcome.toStatus(
                        source.id.toMetadataSource(),
                        (outcome as? SourceOutcome.Answered)?.run { answer.people.size } ?: 0,
                    )
                },
        )
    }

    /** Every people source the operator routed to contributors, in that order. */
    private fun routed(): List<PersonFindSource> {
        val order = routes.domainOrder.getValue(MetadataDomain.CONTRIBUTORS)
        val allowed = routes.providersFor(MetadataDomain.CONTRIBUTORS)
        return registry
            .capable<PersonFindSource>()
            .filter { it.id in allowed }
            .sortedBy { source -> order.indexOf(source.id).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    private suspend fun ask(
        source: PersonFindSource,
        subject: PeopleSubject,
        query: String?,
        locale: MetadataLocale,
    ): SourceOutcome<PersonAnswer> {
        val lookup = subject.lookupFor(source.id, query)
        val key = PeopleFindCache.Key(source.id, lookup, locale.region)
        return askSource(
            source = source.id,
            label = "people",
            deadline = deadline,
            availability = { source.personAvailability() },
            cached = { cache.get(key) },
            fetch = { source.findPeople(lookup, locale) },
            remember = { cache.put(key, it) },
        )
    }

    /** The steps taken: each source's link, then your books (how many), then the name, which always runs when there is one. */
    private fun steps(
        asked: List<Pair<PersonFindSource, SourceOutcome<PersonAnswer>>>,
        subject: PeopleSubject,
        query: String?,
    ): List<PersonSearchStep> {
        val ran =
            asked.mapNotNull { (source, outcome) ->
                (outcome as? SourceOutcome.Answered)?.let { source to it.answer.steps }
            }
        return buildList {
            ran
                .filter { (_, steps) -> PersonStep.LINK in steps }
                .forEach { (source, _) -> add(PersonSearchStep.ExistingLink(source.id.toMetadataSource())) }
            if (ran.any { PersonStep.VIA_BOOKS in it.second }) {
                add(
                    PersonSearchStep.ViaYourBooks(subject.lookupBooks.size),
                )
            }
            if (ran.any { PersonStep.NAME in it.second }) add(PersonSearchStep.ByName(query ?: subject.name))
        }.distinct()
    }
}
