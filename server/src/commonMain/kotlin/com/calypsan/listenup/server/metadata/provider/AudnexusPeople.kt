package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.audnexus.AudnexusBook
import com.calypsan.listenup.server.metadata.spi.ContributorHitMeta
import com.calypsan.listenup.server.metadata.spi.CO_CREDIT_NAME_SIMILARITY
import com.calypsan.listenup.server.metadata.spi.ContributorHitRanker
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.metadata.spi.FoundPerson
import com.calypsan.listenup.server.metadata.spi.PersonAnswer
import com.calypsan.listenup.server.metadata.spi.PersonLookup
import com.calypsan.listenup.server.metadata.spi.PersonStep

private const val MAX_PEOPLE = 5

/**
 * How many of the person's books a Find reads from Audnexus itself; the rest count only when already cached. Every
 * Audnexus read waits its turn at one a second, so five uncached books alone used to spend most of the Find's eight
 * seconds before the name search had begun. Two books are enough to name the person and to share a book with
 * another source's answer.
 */
private const val MAX_BOOK_FETCHES = 2

/**
 * One Audnexus people Find over three cached reads: [search] by name, a [book]'s Audible credits by ASIN, and a
 * person's [profile] by ASIN. Audnexus profiles are Audible's author pages, so everyone it finds is an author. A
 * failed search or book read fails the answer, as a Find source's sub-step does; a profile that can't be read just
 * has no photo.
 *
 * Every read that reaches Audnexus waits at its one-a-second rate limit, so a Find spends its reads where they
 * change the answer: a book's credits come from [cachedBook] when the cache holds them, and from [book] for at
 * most [MAX_BOOK_FETCHES] books that it doesn't (on a cold cache, fewer of your books are counted); and someone
 * found only through those credits, named unlike the person, is listed without a profile read — the ranking hides
 * such a co-credit anyway.
 */
internal class AudnexusPeople(
    private val search: suspend (String) -> AppResult<List<ContributorHitMeta>>,
    private val book: suspend (String) -> AppResult<AudnexusBook?>,
    private val cachedBook: suspend (String) -> AppResult<AudnexusBook?>?,
    private val profile: suspend (String) -> AppResult<ContributorMeta?>,
) {
    suspend fun find(lookup: PersonLookup): AppResult<PersonAnswer> {
        val steps = mutableSetOf<PersonStep>()
        val linked = lookup.keys.distinct()
        if (linked.isNotEmpty()) steps += PersonStep.LINK

        val credited = linkedMapOf<String, MutableSet<String>>()
        val creditNames = mutableMapOf<String, String>()
        var fetched = 0
        lookup.books.forEach { libraryBook ->
            val asin =
                libraryBook.asin ?: libraryBook.refs.firstOrNull { it.provider == ExternalRef.AUDIBLE }?.id
                    ?: return@forEach
            steps += PersonStep.VIA_BOOKS
            val read =
                cachedBook(asin)
                    ?: if (fetched < MAX_BOOK_FETCHES) book(asin).also { fetched++ } else return@forEach
            val found =
                when (read) {
                    is AppResult.Success -> read.data
                    is AppResult.Failure -> return read
                }
            found?.authors.orEmpty().forEach { author ->
                val key = author.asin?.takeIf { it.isNotBlank() } ?: return@forEach
                credited.getOrPut(key) { mutableSetOf() } += libraryBook.bookId
                creditNames.getOrPut(key) { author.name }
            }
        }

        val hits =
            lookup.name
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { name ->
                    steps += PersonStep.NAME
                    when (val searched = search(name)) {
                        is AppResult.Success -> searched.data.take(MAX_PEOPLE)
                        is AppResult.Failure -> return searched
                    }
                }.orEmpty()
        val hitNames = hits.associate { it.key to it.name }

        val keys = (linked + hits.map { it.key } + credited.keys).distinct().take(MAX_PEOPLE + linked.size)
        val people =
            keys.mapNotNull { key ->
                val worthReading = key in linked || key in hitNames || creditNames[key].namedLike(lookup.name)
                val profile = if (worthReading) (profile(key) as? AppResult.Success)?.data else null
                val name =
                    profile?.run { this.name.takeIf { it.isNotBlank() } }
                        ?: hitNames[key]
                        ?: creditNames[key]
                        ?: return@mapNotNull null
                FoundPerson(
                    key = key,
                    name = name,
                    roles = setOf(ContributorRole.AUTHOR),
                    photoUrl = profile?.imageUrl,
                    creditedBookIds = credited[key].orEmpty(),
                    viaLink = key in linked,
                    foundByName = key in hitNames,
                )
            }
        return AppResult.Success(PersonAnswer(people, steps))
    }
}

/** Whether this credited name is like [name] — or there is no name to tell a co-credit by. */
private fun String?.namedLike(name: String): Boolean =
    name.isBlank() || ContributorHitRanker.nameSimilarity(this.orEmpty(), name) >= CO_CREDIT_NAME_SIMILARITY
