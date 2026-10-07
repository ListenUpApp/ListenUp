package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.RoleCoverage
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
        test("candidates split by tier, with the person-specific flags carried to the rows") {
            val outcome = choosePersonFindOutcome(personFindResult()).shouldBeInstanceOf<PersonFindOutcome.Candidates>()
            outcome.strong shouldHaveSize 1
            outcome.maybe shouldHaveSize 1
            outcome.strong.single().shownRole shouldBe ContributorRole.NARRATOR
            outcome.maybe.single().isDifferentRole shouldBe true
            outcome.maybe.single().shownRole shouldBe ContributorRole.AUTHOR
            outcome.maybe.single().noBooksInLibrary shouldBe true
            outcome.partialFailure.shouldBeNull()
        }

        test("the coverage note names the first source without profiles and the ones searched instead") {
            val outcome = choosePersonFindOutcome(personFindResult()).shouldBeInstanceOf<PersonFindOutcome.Candidates>()
            outcome.coverageNote shouldBe CoverageNote(withoutProfiles = listOf(AUDIBLE), using = listOf(HARDCOVER))
        }

        test("no coverage note when the route's first source has profiles for the role") {
            val result =
                personFindResult(
                    role = ContributorRole.AUTHOR,
                    coverage = listOf(RoleCoverage(AUDIBLE, true), RoleCoverage(HARDCOVER, true)),
                )
            choosePersonFindOutcome(result).shouldBeInstanceOf<PersonFindOutcome.Candidates>().coverageNote.shouldBeNull()
        }

        test("no candidates and every covering source answered empty is No profiles") {
            val result = personFindResult(candidates = emptyList())
            val outcome = choosePersonFindOutcome(result).shouldBeInstanceOf<PersonFindOutcome.NoProfiles>()
            outcome.coverageNote shouldBe CoverageNote(listOf(AUDIBLE), listOf(HARDCOVER))
        }

        test("no candidates and no source covering the role at all is No profiles, without a note") {
            val result =
                personFindResult(
                    candidates = emptyList(),
                    coverage = listOf(RoleCoverage(AUDIBLE, false)),
                    sources = listOf(SourceStatus.Unavailable(AUDIBLE, UnavailableReason.NO_PROFILES_FOR_ROLE)),
                )
            choosePersonFindOutcome(result).shouldBeInstanceOf<PersonFindOutcome.NoProfiles>().coverageNote.shouldBeNull()
        }

        test("no candidates because the covering source timed out is a failure with Retry, not No profiles") {
            val result =
                personFindResult(
                    candidates = emptyList(),
                    sources =
                        listOf(
                            SourceStatus.Unavailable(AUDIBLE, UnavailableReason.NO_PROFILES_FOR_ROLE),
                            SourceStatus.TimedOut(HARDCOVER),
                        ),
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
                personFindResult(
                    role = ContributorRole.AUTHOR,
                    sources = listOf(SourceStatus.Answered(AUDIBLE, 2), SourceStatus.Failed(HARDCOVER)),
                )
            val outcome = choosePersonFindOutcome(result).shouldBeInstanceOf<PersonFindOutcome.Candidates>()
            outcome.partialFailure shouldBe PartialFailure(failed = listOf(HARDCOVER), answered = listOf(AUDIBLE))
        }
    })
