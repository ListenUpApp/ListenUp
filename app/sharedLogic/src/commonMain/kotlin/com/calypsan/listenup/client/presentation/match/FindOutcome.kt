package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.PersonFindResult
import com.calypsan.listenup.api.dto.match.SourceStatus
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError

/** What one Find produced: candidates to show, or the single failure that explains why there are none. */
internal sealed interface FindOutcome {
    /** Candidates to show, split by tier, with the partial-failure banner if some sources failed. */
    data class Candidates(
        val strong: List<CandidateUi>,
        val maybe: List<CandidateUi>,
        val partialFailure: PartialFailure?,
    ) : FindOutcome

    /** No candidates: the one failure that explains why. */
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
    if (result.candidates.isNotEmpty()) {
        val candidates = result.candidates.map { it.toUi() }
        return FindOutcome.Candidates(
            strong = candidates.filter { it.tier == MatchTier.STRONG },
            maybe = candidates.filter { it.tier == MatchTier.MAYBE },
            partialFailure = partialFailureOf(result.sources),
        )
    }
    result.sources.filterIsInstance<SourceStatus.NotFoundInStore>().firstOrNull()?.let {
        return FindOutcome.Failure(FindFailure.NotFoundInStore(it.source, it.region, it.suggestedRegions))
    }
    return FindOutcome.Failure(decidingFailureOf(result.sources) ?: FindFailure.NothingFound)
}

/** What one people Find produced: people to show, "No source has a profile", or the failure that explains none. */
internal sealed interface PersonFindOutcome {
    /** People to show, split by tier, with the partial-failure banner. */
    data class Candidates(
        val strong: List<PersonCandidateUi>,
        val maybe: List<PersonCandidateUi>,
        val partialFailure: PartialFailure?,
    ) : PersonFindOutcome

    /** No source has a profile for this person: every source answered empty. */
    data object NoProfiles : PersonFindOutcome

    /** No people because a source failed: the one failure that explains why. */
    data class Failure(
        val failure: FindFailure,
    ) : PersonFindOutcome
}

/**
 * The people counterpart of [chooseFindOutcome], sharing its failure rules. No candidates with nothing failed is
 * [PersonFindOutcome.NoProfiles] (decision 7) — never when a failed source might have had them, which keeps Retry.
 */
internal fun choosePersonFindOutcome(result: PersonFindResult): PersonFindOutcome {
    if (result.candidates.isNotEmpty()) {
        val candidates = result.candidates.map { it.toUi() }
        return PersonFindOutcome.Candidates(
            strong = candidates.filter { it.tier == MatchTier.STRONG },
            maybe = candidates.filter { it.tier == MatchTier.MAYBE },
            partialFailure = partialFailureOf(result.sources),
        )
    }
    return decidingFailureOf(result.sources)?.let(PersonFindOutcome::Failure) ?: PersonFindOutcome.NoProfiles
}

/** The partial banner: the failed sources, and the sources that answered with something. */
private fun partialFailureOf(sources: List<SourceStatus>): PartialFailure? {
    val failed = sources.filter { it.isFailure() }
    if (failed.isEmpty()) return null
    return PartialFailure(
        failed = failed.map(SourceStatus::source),
        answered =
            sources
                .filterIsInstance<SourceStatus.Answered>()
                .filter { it.results > 0 }
                .map(SourceStatus::source),
    )
}

/**
 * The failure that explains an empty Find: the first failed source in the server's (route) order. Rate limits
 * count down to the latest `retryAfter` among every rate-limited source, so Retry never fires into a limit still
 * in force. Null when nothing failed.
 */
private fun decidingFailureOf(sources: List<SourceStatus>): FindFailure? {
    val failed = sources.filter { it.isFailure() }
    return when (val deciding = failed.firstOrNull()) {
        null -> {
            null
        }

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
}

/** A whole-call failure: offline is its own screen; anything else carries its typed error. */
internal fun findFailureFor(error: AppError): FindFailure =
    if (error is TransportError.NetworkUnavailable) FindFailure.Offline else FindFailure.Unexpected(error)

private fun SourceStatus.isFailure(): Boolean =
    this is SourceStatus.TimedOut || this is SourceStatus.RateLimited || this is SourceStatus.Failed
