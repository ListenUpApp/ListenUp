package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.audnexus.AudnexusBook
import com.calypsan.listenup.server.metadata.spi.ContributorHitMeta
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.metadata.spi.FoundPerson
import com.calypsan.listenup.server.metadata.spi.PersonAnswer
import com.calypsan.listenup.server.metadata.spi.PersonLookup
import com.calypsan.listenup.server.metadata.spi.PersonStep

private const val MAX_PEOPLE = 5

/**
 * One Audnexus people Find over three cached reads: [search] by name, a [book]'s Audible credits by ASIN, and a
 * person's [profile] by ASIN. Every person is an author (Audnexus has no narrator profiles). A failed search or
 * book read fails the answer, as a Find source's sub-step does; a profile that can't be read just has no photo.
 */
internal class AudnexusPeople(
    private val search: suspend (String) -> AppResult<List<ContributorHitMeta>>,
    private val book: suspend (String) -> AppResult<AudnexusBook?>,
    private val profile: suspend (String) -> AppResult<ContributorMeta?>,
) {
    suspend fun find(lookup: PersonLookup): AppResult<PersonAnswer> {
        val steps = mutableSetOf<PersonStep>()
        val linked = lookup.keys.distinct()
        if (linked.isNotEmpty()) steps += PersonStep.LINK

        val credited = linkedMapOf<String, MutableSet<String>>()
        val creditNames = mutableMapOf<String, String>()
        lookup.books.forEach { libraryBook ->
            val asin = libraryBook.asin ?: libraryBook.refs.firstOrNull { it.provider == ExternalRef.AUDIBLE }?.id ?: return@forEach
            steps += PersonStep.VIA_BOOKS
            val found =
                when (val read = book(asin)) {
                    is AppResult.Success -> read.data
                    is AppResult.Failure -> return read
                }
            found?.authors.orEmpty().forEach { author ->
                val key = author.asin?.takeIf { it.isNotBlank() } ?: return@forEach
                credited.getOrPut(key) { mutableSetOf() } += libraryBook.bookId
                creditNames.putIfAbsent(key, author.name)
            }
        }

        val hits =
            lookup.name.trim().takeIf { it.isNotEmpty() }?.let { name ->
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
                val profile = (profile(key) as? AppResult.Success)?.data
                val name = profile?.name?.takeIf { it.isNotBlank() } ?: hitNames[key] ?: creditNames[key] ?: return@mapNotNull null
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
