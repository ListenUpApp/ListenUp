package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.spi.FindAvailability
import com.calypsan.listenup.server.metadata.spi.FoundPerson
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.PersonAnswer
import com.calypsan.listenup.server.metadata.spi.PersonFindSource
import com.calypsan.listenup.server.metadata.spi.PersonLibraryBook
import com.calypsan.listenup.server.metadata.spi.PersonLookup
import com.calypsan.listenup.server.metadata.spi.PersonStep
import com.calypsan.listenup.server.metadata.spi.ContributorHitMeta
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import kotlinx.coroutines.delay
import kotlin.time.Duration

/** A library book crediting the subject in [roles]; identified by ASIN unless [asin] is null. */
internal fun libraryBook(
    id: String,
    title: String = id,
    asin: String? = "A-$id",
    refs: List<ExternalRef> = emptyList(),
    roles: Set<ContributorRole> = setOf(ContributorRole.NARRATOR),
) = PersonLibraryBook(bookId = id, title = title, asin = asin, isbn = null, refs = refs, roles = roles)

/** Ray Porter, credited on [books] in this library — by default narrating two. */
internal fun porterSubject(
    refs: List<ExternalRef> = emptyList(),
    books: List<PersonLibraryBook> =
        listOf(libraryBook("phm", "Project Hail Mary"), libraryBook("hr", "Heaven's River")),
) = PeopleSubject(contributorId = "c-porter", name = "Ray Porter", refs = refs, books = books)

internal fun person(
    key: String,
    name: String = "Ray Porter",
    roles: Set<ContributorRole> = setOf(ContributorRole.NARRATOR),
    credited: Set<String> = emptySet(),
    foundByName: Boolean = true,
    viaLink: Boolean = false,
    knownWorks: List<String> = emptyList(),
    photoUrl: String? = null,
) = FoundPerson(
    key = key,
    name = name,
    roles = roles,
    photoUrl = photoUrl,
    knownWorks = knownWorks,
    creditedBookIds = credited,
    viaLink = viaLink,
    foundByName = foundByName,
)

/** A scripted people source with in-memory state: [answer] replies to each lookup, recorded in [asked]. */
internal class FakePeopleSource(
    override val id: MetadataProviderId,
    var latency: Duration = Duration.ZERO,
    var availability: FindAvailability = FindAvailability.Available,
    var answer: (
        PersonLookup,
    ) -> AppResult<PersonAnswer> = { AppResult.Success(PersonAnswer(emptyList(), setOf(PersonStep.NAME))) },
) : PersonFindSource {
    val asked = mutableListOf<PersonLookup>()

    override suspend fun personAvailability(): FindAvailability = availability

    override suspend fun findPeople(
        lookup: PersonLookup,
        locale: MetadataLocale,
    ): AppResult<PersonAnswer> {
        asked += lookup
        if (latency > Duration.ZERO) delay(latency)
        return answer(lookup)
    }

    fun answers(
        people: List<FoundPerson>,
        steps: Set<PersonStep> = setOf(PersonStep.NAME),
    ) {
        answer = { AppResult.Success(PersonAnswer(people, steps)) }
    }

    override suspend fun searchContributors(
        name: String,
        locale: MetadataLocale,
    ): AppResult<List<ContributorHitMeta>> = AppResult.Success(emptyList())

    override suspend fun getContributor(
        key: String,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ContributorMeta?> = AppResult.Success(null)
}
