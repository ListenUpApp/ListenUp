package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.LibraryCredit
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

/** People are tiered by your own books, in any role: Strong needs a book of yours, or the existing link. */
class PeopleRankerTest :
    FunSpec({
        test("credited on your books is Strong, and best, with what they did on them here") {
            val porter = rank(porterSubject(), person("250716", credited = setOf("phm", "hr"))).single()
            porter.tier shouldBe MatchTier.STRONG
            porter.libraryCount shouldBe 2
            porter.isBest shouldBe true
            porter.reasons shouldBe emptyList()
            porter.libraryCredits shouldBe listOf(LibraryCredit(ContributorRole.NARRATOR, 2))
        }

        test("on none of your books is Maybe, with NoBooksInLibrary") {
            val other = rank(porterSubject(), person("1")).single()
            other.tier shouldBe MatchTier.MAYBE
            other.isBest shouldBe false
            other.reasons shouldBe listOf(PersonReason.NoBooksInLibrary)
        }

        test("a source listing them only as an author is still Strong for the narrator of your books") {
            val author =
                rank(porterSubject(), person("2", roles = setOf(ContributorRole.AUTHOR), credited = setOf("phm"))).single()
            author.tier shouldBe MatchTier.STRONG
            author.roles shouldBe listOf(ContributorRole.AUTHOR)
            author.reasons shouldBe emptyList()
            author.libraryCredits shouldBe listOf(LibraryCredit(ContributorRole.NARRATOR, 1))
        }

        test("the evidence counts every role they hold here, most first, a book in two roles in both") {
            val subject =
                porterSubject(
                    books =
                        listOf(
                            libraryBook("w1", roles = setOf(ContributorRole.AUTHOR)),
                            libraryBook("w2", roles = setOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR)),
                            libraryBook("w3", roles = setOf(ContributorRole.AUTHOR)),
                            libraryBook("n1", roles = setOf(ContributorRole.NARRATOR)),
                            libraryBook("t1", roles = setOf(ContributorRole.TRANSLATOR)),
                            libraryBook("elsewhere", roles = setOf(ContributorRole.EDITOR)),
                        ),
                )
            val credited = rank(subject, person("5", credited = setOf("w1", "w2", "w3", "n1", "t1"))).single()
            credited.libraryCount shouldBe 5
            credited.libraryCredits shouldBe
                listOf(
                    LibraryCredit(ContributorRole.AUTHOR, 3),
                    LibraryCredit(ContributorRole.NARRATOR, 2),
                    LibraryCredit(ContributorRole.TRANSLATOR, 1),
                )
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
