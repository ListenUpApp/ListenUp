package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.LibraryCredit
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.SourceStatus
import com.calypsan.listenup.api.dto.match.UnavailableReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * [choosePersonFindOutcome] — the one place deciding between people, "No source has a profile", and a failure
 * screen. Decision 7's honest no-profile path must never swallow a failure that Retry could fix.
 */
class PersonFindOutcomeTest :
    FunSpec({
        test("candidates split by tier, with the person-specific evidence carried to the rows") {
            val outcome = choosePersonFindOutcome(personFindResult()).shouldBeInstanceOf<PersonFindOutcome.Candidates>()
            outcome.strong shouldHaveSize 1
            outcome.maybe shouldHaveSize 1
            outcome.strong.single().shownRole shouldBe ContributorRole.NARRATOR
            outcome.strong.single().libraryCredits shouldBe listOf(LibraryCredit(ContributorRole.NARRATOR, 5))
            outcome.maybe.single().shownRole shouldBe ContributorRole.AUTHOR
            outcome.maybe.single().noBooksInLibrary shouldBe true
            outcome.partialFailure.shouldBeNull()
        }

        test("a row names the role they hold on most of your books when the source lists it, else the source's first") {
            val both =
                personCandidate(
                    roles = listOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR),
                    libraryCredits =
                        listOf(LibraryCredit(ContributorRole.TRANSLATOR, 4), LibraryCredit(ContributorRole.NARRATOR, 2)),
                )
            val onlyAuthor =
                personCandidate(
                    key = personKey(id = "other"),
                    roles = listOf(ContributorRole.AUTHOR),
                    libraryCredits = listOf(LibraryCredit(ContributorRole.NARRATOR, 3)),
                )
            val rows =
                choosePersonFindOutcome(personFindResult(candidates = listOf(both, onlyAuthor)))
                    .shouldBeInstanceOf<PersonFindOutcome.Candidates>()
                    .strong
            rows.map { it.shownRole } shouldBe listOf(ContributorRole.NARRATOR, ContributorRole.AUTHOR)
        }

        test("no candidates and every source answered empty is No profiles") {
            choosePersonFindOutcome(personFindResult(candidates = emptyList())) shouldBe PersonFindOutcome.NoProfiles
        }

        test("no candidates with a source unavailable and none failed is still No profiles") {
            val result =
                personFindResult(
                    candidates = emptyList(),
                    sources =
                        listOf(
                            SourceStatus.Answered(AUDIBLE, 0),
                            SourceStatus.Unavailable(HARDCOVER, UnavailableReason.NOT_CONFIGURED),
                        ),
                )
            choosePersonFindOutcome(result) shouldBe PersonFindOutcome.NoProfiles
        }

        test("no candidates because a source timed out is a failure with Retry, not No profiles") {
            val result =
                personFindResult(
                    candidates = emptyList(),
                    sources = listOf(SourceStatus.Answered(AUDIBLE, 0), SourceStatus.TimedOut(HARDCOVER)),
                )
            choosePersonFindOutcome(result).shouldBeInstanceOf<PersonFindOutcome.Failure>().failure shouldBe
                FindFailure.TimedOut(HARDCOVER)
        }

        test("a rate limit counts down to the latest retry-after") {
            val result =
                personFindResult(
                    candidates = emptyList(),
                    sources = listOf(SourceStatus.RateLimited(HARDCOVER, 30), SourceStatus.RateLimited(AUDIBLE, 45)),
                )
            choosePersonFindOutcome(result).shouldBeInstanceOf<PersonFindOutcome.Failure>().failure shouldBe
                FindFailure.RateLimited(HARDCOVER, 45)
        }

        test("a failed source beside candidates is the partial banner") {
            val result =
                personFindResult(sources = listOf(SourceStatus.Answered(AUDIBLE, 2), SourceStatus.Failed(HARDCOVER)))
            val outcome = choosePersonFindOutcome(result).shouldBeInstanceOf<PersonFindOutcome.Candidates>()
            outcome.partialFailure shouldBe PartialFailure(failed = listOf(HARDCOVER), answered = listOf(AUDIBLE))
            outcome.strong.single().tier shouldBe MatchTier.STRONG
        }
    })
