package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.SourceStatus
import com.calypsan.listenup.api.dto.match.UnavailableReason
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.metadata.MetadataLocale
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** [chooseFindOutcome] — the one function that picks results or a failure screen, for every status mix. */
class FindOutcomeTest :
    FunSpec({
        val uk = MetadataLocale("uk")
        val us = MetadataLocale("us")
        val au = MetadataLocale("au")

        test("candidates split into Strong and Maybe, in the server's order, with no banner when all answered") {
            val outcome = chooseFindOutcome(findResult()).shouldBeInstanceOf<FindOutcome.Candidates>()
            outcome.strong.map { it.key } shouldBe listOf(BEST, HC_ONLY)
            outcome.maybe.map { it.key } shouldBe listOf(DRAMATIZED)
            outcome.strong.all { it.tier == MatchTier.STRONG } shouldBe true
            outcome.partialFailure shouldBe null
        }

        test("one source failing beside answers is a partial banner naming who failed and who answered") {
            val outcome =
                chooseFindOutcome(
                    findResult(
                        sources =
                            listOf(
                                SourceStatus.Answered(AUDIBLE, 2),
                                SourceStatus.TimedOut(HARDCOVER),
                                SourceStatus.Answered(ITUNES, 1),
                            ),
                    ),
                ).shouldBeInstanceOf<FindOutcome.Candidates>()
            outcome.partialFailure shouldBe PartialFailure(failed = listOf(HARDCOVER), answered = listOf(AUDIBLE, ITUNES))
        }

        test("a source that is merely not configured is not a failure") {
            val outcome =
                chooseFindOutcome(
                    findResult(
                        sources =
                            listOf(
                                SourceStatus.Answered(AUDIBLE, 2),
                                SourceStatus.Unavailable(HARDCOVER, UnavailableReason.NOT_CONFIGURED),
                            ),
                    ),
                ).shouldBeInstanceOf<FindOutcome.Candidates>()
            outcome.partialFailure shouldBe null
        }

        test("no candidates and the store answered empty is not found, with its suggestions") {
            val outcome =
                chooseFindOutcome(
                    findResult(
                        candidates = emptyList(),
                        sources = listOf(SourceStatus.TimedOut(HARDCOVER), SourceStatus.NotFoundInStore(AUDIBLE, uk, listOf(us, au))),
                    ),
                )
            outcome shouldBe FindOutcome.Failure(FindFailure.NotFoundInStore(AUDIBLE, uk, listOf(us, au)))
        }

        test("no candidates: the first failed source in route order decides") {
            chooseFindOutcome(
                findResult(
                    candidates = emptyList(),
                    sources = listOf(SourceStatus.TimedOut(AUDIBLE), SourceStatus.RateLimited(HARDCOVER, 30)),
                ),
            ) shouldBe FindOutcome.Failure(FindFailure.TimedOut(AUDIBLE))
        }

        test("a rate limit counts down to the latest retry-after among every limited source") {
            chooseFindOutcome(
                findResult(
                    candidates = emptyList(),
                    sources = listOf(SourceStatus.RateLimited(HARDCOVER, 30), SourceStatus.RateLimited(AUDIBLE, 45)),
                ),
            ) shouldBe FindOutcome.Failure(FindFailure.RateLimited(HARDCOVER, 45))
        }

        test("an outright source failure is its own screen") {
            chooseFindOutcome(findResult(candidates = emptyList(), sources = listOf(SourceStatus.Failed(AUDIBLE)))) shouldBe
                FindOutcome.Failure(FindFailure.SourceFailed(AUDIBLE))
        }

        test("every source answered empty is nothing found") {
            chooseFindOutcome(findResult(candidates = emptyList(), sources = listOf(SourceStatus.Answered(AUDIBLE, 0)))) shouldBe
                FindOutcome.Failure(FindFailure.NothingFound)
        }

        test("offline is its own failure; any other whole-call error keeps its type") {
            findFailureFor(TransportError.NetworkUnavailable()) shouldBe FindFailure.Offline
            val unavailable = MetadataError.ExternalUnavailable()
            findFailureFor(unavailable) shouldBe FindFailure.Unexpected(unavailable)
        }
    })
