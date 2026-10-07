package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.SourceStatus
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError

/** What one Find produced: candidates to show, or the single failure that explains why there are none. */
internal sealed interface FindOutcome {
    data class Candidates(
        val strong: List<CandidateUi>,
        val maybe: List<CandidateUi>,
        val partialFailure: PartialFailure?,
    ) : FindOutcome

    data class Failure(
        val failure: FindFailure,
    ) : FindOutcome
}

/**
 * The one place that decides between results and a failure screen, so partial and total failure can't drift
 * apart between platforms.
 *
 * - Any candidate → results; a source that timed out, was rate-limited or failed becomes the partial banner.
 * - No candidates and the store answered empty → not found in that store.
 * - Otherwise the first failed source, in the server's (route) order, decides. Rate limits count down to the
 *   latest `retryAfter` among every rate-limited source, so Retry never fires into a limit still in force.
 * - No candidates and nothing failed → nothing found.
 */
internal fun chooseFindOutcome(result: BookFindResult): FindOutcome {
    val failed = result.sources.filter { it.isFailure() }
    if (result.candidates.isNotEmpty()) {
        val candidates = result.candidates.map { it.toUi() }
        val partial =
            failed.takeIf { it.isNotEmpty() }?.let {
                PartialFailure(
                    failed = it.map(SourceStatus::source),
                    answered =
                        result.sources
                            .filterIsInstance<SourceStatus.Answered>()
                            .filter { answered -> answered.results > 0 }
                            .map(SourceStatus::source),
                )
            }
        return FindOutcome.Candidates(
            strong = candidates.filter { it.tier == MatchTier.STRONG },
            maybe = candidates.filter { it.tier == MatchTier.MAYBE },
            partialFailure = partial,
        )
    }
    result.sources.filterIsInstance<SourceStatus.NotFoundInStore>().firstOrNull()?.let {
        return FindOutcome.Failure(FindFailure.NotFoundInStore(it.source, it.region, it.suggestedRegions))
    }
    val deciding = failed.firstOrNull() ?: return FindOutcome.Failure(FindFailure.NothingFound)
    val failure =
        when (deciding) {
            is SourceStatus.TimedOut -> {
                FindFailure.TimedOut(deciding.source)
            }

            is SourceStatus.RateLimited -> {
                val latest = failed.filterIsInstance<SourceStatus.RateLimited>().maxOf { it.retryAfterSeconds }
                FindFailure.RateLimited(deciding.source, latest.toInt().coerceAtLeast(0))
            }

            else -> {
                FindFailure.SourceFailed(deciding.source)
            }
        }
    return FindOutcome.Failure(failure)
}

/** A whole-call failure: offline is its own screen; anything else carries its typed error. */
internal fun findFailureFor(error: AppError): FindFailure =
    if (error is TransportError.NetworkUnavailable) FindFailure.Offline else FindFailure.Unexpected(error)

private fun SourceStatus.isFailure(): Boolean =
    this is SourceStatus.TimedOut || this is SourceStatus.RateLimited || this is SourceStatus.Failed
