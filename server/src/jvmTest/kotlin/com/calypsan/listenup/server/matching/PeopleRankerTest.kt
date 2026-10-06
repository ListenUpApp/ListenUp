package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.PersonReason
import com.calypsan.listenup.server.metadata.spi.FoundPerson
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun rank(
    subject: PeopleSubject,
    vararg people: FoundPerson,
) = PeopleRanker.rank(subject, PeopleMerger.merge(people.map { SourcedPerson(MetadataProviderId.HARDCOVER, it) }))

/** People are tiered by your own books (spec): Strong needs the right role and a book of yours, or a link. */
class PeopleRankerTest :
    FunSpec({
        test("the right role on your books is Strong, and best") {
            val porter = rank(porterSubject(), person("250716", credited = setOf("phm", "hr"))).single()
            porter.tier shouldBe MatchTier.STRONG
            porter.libraryCount shouldBe 2
            porter.isBest shouldBe true
            porter.reasons shouldBe emptyList()
        }

        test("the right role on none of your books is Maybe, with NoBooksInLibrary") {
            val other = rank(porterSubject(), person("1")).single()
            other.tier shouldBe MatchTier.MAYBE
            other.isBest shouldBe false
            other.reasons shouldBe listOf(PersonReason.NoBooksInLibrary)
        }

        test("someone not credited in the role is Maybe with DifferentRole, even on your books") {
            val author =
                rank(porterSubject(), person("2", roles = setOf(ContributorRole.AUTHOR), credited = setOf("phm"))).single()
            author.tier shouldBe MatchTier.MAYBE
            author.reasons shouldBe listOf(PersonReason.DifferentRole(listOf(ContributorRole.AUTHOR)))
        }

        test("the existing link is Strong whatever the evidence") {
            rank(porterSubject(), person("3", viaLink = true, foundByName = false)).single().tier shouldBe MatchTier.STRONG
        }

        test("a book with no identifier counts when its title is among their known works") {
            val subject = porterSubject(books = listOf(libraryBook("old", "Heaven's River", asin = null)))
            rank(subject, person("4", knownWorks = listOf("Heavens River"))).single().libraryCount shouldBe 1
        }

        test("Strong first, then the most of your books; a co-credit not named like them is left out") {
            val ranked =
                rank(
                    porterSubject(),
                    person("few", credited = setOf("phm")),
                    person("none"),
                    person("many", credited = setOf("phm", "hr")),
                    person("coauthor", name = "Andy Weir", credited = setOf("phm"), foundByName = false),
                )
            ranked.map {
                it.key.refs
                    .single()
                    .id
            } shouldBe listOf("many", "few", "none")
        }
    })
