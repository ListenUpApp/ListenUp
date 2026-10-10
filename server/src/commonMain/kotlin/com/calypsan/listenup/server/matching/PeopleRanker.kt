package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.match.LibraryCredit
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.PersonCandidate
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonReason
import com.calypsan.listenup.server.metadata.spi.CO_CREDIT_NAME_SIMILARITY
import com.calypsan.listenup.server.metadata.spi.ContributorHitRanker
import com.calypsan.listenup.server.metadata.spi.toMetadataSource

/**
 * Ranks merged people against the [PeopleSubject] (spec, *Find and Review for people*), in no role in particular:
 * - **Library count:** the subject's books the sources credit to this person, in any role — plus, for a book
 *   with no identifier a source could check, one whose title is among the person's known works. **Library
 *   credits** say what the subject did on those books here: "Narrated 3 of your books · Translated 1".
 * - **Strong** needs a library count of at least one, or the person's existing link. A source listing them in
 *   another role than yours is no evidence against them — someone who wrote a foreword may well be an author.
 * - **Reasons:** `NoBooksInLibrary` at a count of zero.
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
                .filter { person ->
                    person.viaLink || person.foundByName ||
                        ContributorHitRanker.nameSimilarity(person.name, subject.name) >= CO_CREDIT_NAME_SIMILARITY
                }.map { person ->
                    val byTitle =
                        unidentified
                            .filter { book -> person.knownWorks.any { titleKey(it) == titleKey(book.title) } }
                            .map { it.bookId }
                    val books = person.creditedBookIds + byTitle
                    val tier = if (person.viaLink || books.isNotEmpty()) MatchTier.STRONG else MatchTier.MAYBE
                    Ranked(
                        person = person,
                        libraryCount = books.size,
                        libraryCredits = creditsOn(subject, books),
                        tier = tier,
                        similarity = ContributorHitRanker.nameSimilarity(person.name, subject.name),
                    ) to listOfNotNull(PersonReason.NoBooksInLibrary.takeIf { books.isEmpty() })
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
                libraryCredits = rankedPerson.libraryCredits,
            )
        }
    }

    /** What the subject did on [bookIds] here, per role, most books first (role order on a tie). */
    private fun creditsOn(
        subject: PeopleSubject,
        bookIds: Set<String>,
    ): List<LibraryCredit> =
        subject.books
            .filter { it.bookId in bookIds }
            .flatMap { it.roles }
            .groupingBy { it }
            .eachCount()
            .map { (role, count) -> LibraryCredit(role, count) }
            .sortedWith(compareByDescending<LibraryCredit> { it.bookCount }.thenBy { it.role.ordinal })

    private data class Ranked(
        val person: MergedPerson,
        val libraryCount: Int,
        val libraryCredits: List<LibraryCredit>,
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
