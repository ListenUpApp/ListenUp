package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.PersonCandidate
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonReason
import com.calypsan.listenup.server.metadata.spi.ContributorHitRanker
import com.calypsan.listenup.server.metadata.spi.toMetadataSource

/** How alike a person found only through your books' credits must be named to be shown (a co-credit isn't them). */
private const val CO_CREDIT_NAME_SIMILARITY = 0.5

/**
 * Ranks merged people against the [PeopleSubject] (spec, *Find and Review for people*):
 * - **Library count:** the subject's books the sources credit to this person in the role — plus, for a book with
 *   no identifier a source could check, one whose title is among the person's known works.
 * - **Strong** needs the right role and a library count of at least one, or the person's existing link.
 * - **Reasons:** `DifferentRole` when no source credits them in the role; `NoBooksInLibrary` at a count of zero.
 * - **Order:** tier, then library count, then how alike the names are; `isBest` is the first Strong.
 * A person found only through your books' credits, not by name or link, is shown only when named like the subject
 * — otherwise they are a co-author or co-narrator of those books.
 */
internal object PeopleRanker {
    fun rank(
        subject: PeopleSubject,
        people: List<MergedPerson>,
    ): List<PersonCandidate> {
        val unidentified = subject.books.filterNot { it.isIdentified() }
        val ranked =
            people
                .filter {
                    it.viaLink || it.foundByName ||
                        ContributorHitRanker.nameSimilarity(it.name, subject.name) >= CO_CREDIT_NAME_SIMILARITY
                }.map { person ->
                    val byTitle =
                        unidentified
                            .filter { book -> person.knownWorks.any { titleKey(it) == titleKey(book.title) } }
                            .map { it.bookId }
                    val libraryCount = (person.creditedBookIds + byTitle).size
                    val inRole = subject.role in person.roles
                    val tier = if (person.viaLink || (inRole && libraryCount > 0)) MatchTier.STRONG else MatchTier.MAYBE
                    Ranked(
                        person,
                        libraryCount,
                        tier,
                        ContributorHitRanker.nameSimilarity(person.name, subject.name),
                    ) to
                        buildList {
                            if (!inRole) add(PersonReason.DifferentRole(person.roles.sortedBy { it.ordinal }))
                            if (libraryCount == 0) add(PersonReason.NoBooksInLibrary)
                        }
                }.sortedWith(
                    compareBy<Pair<Ranked, List<PersonReason>>> { it.first.tier.ordinal }
                        .thenByDescending { it.first.libraryCount }
                        .thenByDescending { it.first.similarity },
                )
        val best = ranked.firstOrNull { it.first.tier == MatchTier.STRONG }
        return ranked.map { (rankedPerson, reasons) ->
            val person = rankedPerson.person
            PersonCandidate(
                key = PersonCandidateKey(person.refs),
                name = person.name,
                roles = person.roles.sortedBy { it.ordinal },
                photoUrl = person.photoUrl,
                knownWorks = person.knownWorks,
                worksCount = person.worksCount,
                libraryCount = rankedPerson.libraryCount,
                foundIn = person.sources.map { it.toMetadataSource() }.distinct(),
                tier = rankedPerson.tier,
                isBest = best?.first === rankedPerson,
                isCurrentLink = person.viaLink,
                reasons = reasons,
            )
        }
    }

    private class Ranked(
        val person: MergedPerson,
        val libraryCount: Int,
        val tier: MatchTier,
        val similarity: Double,
    )

    /** A title's comparison key: lower-cased, apostrophes dropped ("Heaven's" is "Heavens"), punctuation as spaces. */
    private fun titleKey(title: String): String =
        title
            .lowercase()
            .replace(Regex("['’]"), "")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
