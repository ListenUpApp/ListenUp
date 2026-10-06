package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.spi.FoundPerson
import com.calypsan.listenup.server.metadata.spi.PersonAnswer
import com.calypsan.listenup.server.metadata.spi.PersonLibraryBook
import com.calypsan.listenup.server.metadata.spi.PersonLookup
import com.calypsan.listenup.server.metadata.spi.PersonStep

private const val MS_PER_SECOND = 1_000L
private const val KNOWN_WORKS = 2
private const val AUTHOR_ROLE = "Author"
private const val HARDCOVER = "hardcover"

/**
 * Hardcover in a people Find (matching redesign PR 4), behind [HardcoverMetadataSource]'s `PersonFindSource`.
 * Hardcover has profiles for narrators as well as authors: a narrator is an ordinary Hardcover author credited
 * on *editions* with a narrator role, an author is credited on *books*.
 *
 * At most two calls (the spec's Risks table): the author index searched by name, then one batched read of the
 * people found (and the person's own ref) with their role counts, plus the credits on the library's books —
 * by ASIN, ISBN and linked Hardcover book. A person credited there in the role searched counts that book.
 */
internal class HardcoverPeople(
    private val graphQl: HardcoverGraphQlClient,
    private val catalogToken: HardcoverCatalogToken,
    private val rateLimiter: HardcoverRateLimiter,
) {
    suspend fun find(lookup: PersonLookup): AppResult<PersonAnswer> {
        val steps = mutableSetOf<PersonStep>()
        val linked = lookup.keys.mapNotNull { it.removePrefix(HARDCOVER_AUTHOR_KEY_PREFIX).toLongOrNull() }.distinct()
        if (linked.isNotEmpty()) steps += PersonStep.LINK
        val name = lookup.name.trim()
        val hits =
            if (name.isEmpty()) {
                emptyList()
            } else {
                steps += PersonStep.NAME
                when (val answer = read { token -> graphQl.searchPeople(token, name) }) {
                    is HardcoverCall.Ok -> answer.value
                    else -> return failure(answer)
                }
            }
        val asins = lookup.books.mapNotNull { it.asin }.distinct()
        val isbns = lookup.books.mapNotNull { it.isbn }.distinct()
        val bookIds = lookup.books.flatMap { it.hardcoverBookIds() }.distinct()
        if (asins.isNotEmpty() || isbns.isNotEmpty() || bookIds.isNotEmpty()) steps += PersonStep.VIA_BOOKS
        val ids = (linked + hits.map { it.id }).distinct()
        if (ids.isEmpty() && PersonStep.VIA_BOOKS !in steps) return AppResult.Success(PersonAnswer(emptyList(), steps))
        val details =
            when (val answer = read { token -> graphQl.peopleDetails(token, ids, asins, isbns, bookIds) }) {
                is HardcoverCall.Ok -> answer.value
                else -> return failure(answer)
            }
        return AppResult.Success(PersonAnswer(people(lookup, linked, hits, details), steps))
    }

    private fun people(
        lookup: PersonLookup,
        linked: List<Long>,
        hits: List<HardcoverPersonHit>,
        details: HardcoverPeopleDetails,
    ): List<FoundPerson> {
        val credited = mutableMapOf<Long, MutableSet<String>>()
        val viaBooks = linkedMapOf<Long, HardcoverPerson>()
        lookup.books.forEach { book ->
            details.editions
                .filter { book.matches(it) }
                .flatMap { it.credits }
                .filter { it.role.isRole(lookup.role) }
                .forEach { credit ->
                    credited.getOrPut(credit.person.id) { mutableSetOf() } += book.bookId
                    viaBooks.putIfAbsent(credit.person.id, credit.person)
                }
        }
        val profiles = details.people.associateBy { it.id } + viaBooks.filterKeys { it !in details.people.map { p -> p.id } }
        val hitsById = hits.associateBy { it.id }
        val order = (linked + hits.map { it.id } + viaBooks.keys).distinct()
        return order.mapNotNull { id ->
            val profile = profiles[id]
            val hit = hitsById[id]
            val name = profile?.name ?: hit?.name ?: return@mapNotNull null
            FoundPerson(
                key = id.toString(),
                name = name,
                roles = rolesOf(profile, credited.containsKey(id), lookup.role),
                photoUrl = profile?.imageUrl ?: hit?.imageUrl,
                knownWorks = hit?.books.orEmpty().take(KNOWN_WORKS),
                worksCount = profile?.booksCount ?: hit?.booksCount,
                creditedBookIds = credited[id].orEmpty(),
                viaLink = id in linked,
                foundByName = id in hitsById,
            )
        }
    }

    /** The roles Hardcover credits [profile] in; a credit in [searched] on one of your books counts too. */
    private fun rolesOf(
        profile: HardcoverPerson?,
        creditedInSearched: Boolean,
        searched: ContributorRole,
    ): Set<ContributorRole> =
        buildSet {
            if ((profile?.narrations ?: 0) > 0) add(ContributorRole.NARRATOR)
            if ((profile?.authorships ?: 0) > 0) add(ContributorRole.AUTHOR)
            if (creditedInSearched) add(searched)
        }

    private suspend fun <T> read(call: suspend (String) -> HardcoverCall<T>): HardcoverCall<T>? =
        catalogToken.read { token ->
            rateLimiter.await()
            call(token)
        }

    private fun failure(answer: HardcoverCall<*>?): AppResult.Failure =
        when (answer) {
            is HardcoverCall.Throttled -> {
                val seconds = answer.retryAfterMs?.let { (it + MS_PER_SECOND - 1) / MS_PER_SECOND }
                AppResult.Failure(MetadataError.ExternalRateLimited(retryAfterSeconds = seconds))
            }

            null -> {
                AppResult.Failure(HardcoverError.Unavailable(debugInfo = "hardcover people: no token can read the catalogue"))
            }

            else -> {
                AppResult.Failure(HardcoverError.Unavailable(debugInfo = "hardcover people: ${answer.describe()}"))
            }
        }

    private fun HardcoverCall<*>.describe(): String =
        when (this) {
            is HardcoverCall.Ok -> "ok"
            HardcoverCall.Unauthorized -> "token rejected"
            is HardcoverCall.MissingScope -> "missing scope ${scope.orEmpty()}"
            is HardcoverCall.Throttled -> "throttled"
            is HardcoverCall.Failed -> detail
        }
}

private fun PersonLibraryBook.hardcoverBookIds(): List<Long> =
    refs.filter { it.provider == HARDCOVER }.mapNotNull { it.id.toLongOrNull() }

/** Whether [edition] is this library book: the same ASIN, an ISBN in common, or its linked Hardcover book. */
private fun PersonLibraryBook.matches(edition: HardcoverCreditedEdition): Boolean =
    (asin != null && edition.asin.equals(asin, ignoreCase = true)) ||
        (isbn != null && isbn in edition.isbns) ||
        (edition.asin == null && edition.isbns.isEmpty() && edition.bookId != null && edition.bookId in hardcoverBookIds())

/** Whether a Hardcover role string is [role]: a narrator variant, or an author's (blank or "Author"). */
private fun String?.isRole(role: ContributorRole): Boolean =
    when (role) {
        ContributorRole.NARRATOR -> this != null && NARRATOR_ROLES.any { it.equals(this, ignoreCase = true) }
        ContributorRole.AUTHOR -> isNullOrBlank() || equals(AUTHOR_ROLE, ignoreCase = true)
        else -> false
    }
