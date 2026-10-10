package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.match.MetadataSource
import com.calypsan.listenup.api.dto.match.SourceStatus
import com.calypsan.listenup.api.dto.match.UnavailableReason
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.metadata.spi.FindAvailability
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

private val logger = loggerFor<SourceOutcome<*>>()

/** How one source fared in one Find — books or people. */
internal sealed interface SourceOutcome<out A> {
    /** It answered with [answer]. */
    data class Answered<A>(
        val answer: A,
    ) : SourceOutcome<A>

    /** It missed the deadline. */
    data object TimedOut : SourceOutcome<Nothing>

    /** It asked us to wait [retryAfterSeconds]. */
    data class RateLimited(
        val retryAfterSeconds: Long,
    ) : SourceOutcome<Nothing>

    /** It errored or threw. */
    data object Failed : SourceOutcome<Nothing>

    /** It couldn't take part, for [reason]. */
    data class Unavailable(
        val reason: UnavailableReason,
    ) : SourceOutcome<Nothing>
}

/**
 * Asks [source] within [deadline], contained: availability first (so a source switched off drops out at once),
 * then [cached], then [fetch], whose full answer is handed to [remember]. A throw is a failure; cancellation is
 * re-raised; a rate limit keeps its retry-after (the canvas's 30 s when the source gave none). A missed deadline is
 * logged with what was being found ([label]: "books", "people") — the Find itself only says "timed out".
 */
internal suspend fun <A> askSource(
    source: MetadataProviderId,
    label: String,
    deadline: Duration,
    availability: suspend () -> FindAvailability,
    cached: () -> A?,
    fetch: suspend () -> AppResult<A>,
    remember: (A) -> Unit,
): SourceOutcome<A> =
    withTimeoutOrNull(deadline) {
        try {
            when (val available = availability()) {
                is FindAvailability.Unavailable -> {
                    SourceOutcome.Unavailable(available.reason)
                }

                FindAvailability.Available -> {
                    cached()?.let { SourceOutcome.Answered(it) } ?: fetch().toOutcome(source).also { outcome ->
                        if (outcome is SourceOutcome.Answered) remember(outcome.answer)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "find: $source threw — reported as failed" }
            SourceOutcome.Failed
        }
    } ?: SourceOutcome.TimedOut.also { logger.warn { "find: $label from $source didn't answer within $deadline" } }

private fun <A> AppResult<A>.toOutcome(source: MetadataProviderId): SourceOutcome<A> =
    when (this) {
        is AppResult.Success -> {
            SourceOutcome.Answered(data)
        }

        is AppResult.Failure -> {
            val failure = error
            if (failure is MetadataError.ExternalRateLimited) {
                SourceOutcome.RateLimited(failure.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS)
            } else {
                logger.warn { "find: $source failed (${failure.code}) — reported as failed" }
                SourceOutcome.Failed
            }
        }
    }

/** How [this] outcome reads on the wire for [shown], given how many [results] an answer held. */
internal fun SourceOutcome<*>.toStatus(
    shown: MetadataSource,
    results: Int,
): SourceStatus =
    when (this) {
        is SourceOutcome.Answered -> SourceStatus.Answered(shown, results)
        SourceOutcome.TimedOut -> SourceStatus.TimedOut(shown)
        is SourceOutcome.RateLimited -> SourceStatus.RateLimited(shown, retryAfterSeconds)
        SourceOutcome.Failed -> SourceStatus.Failed(shown)
        is SourceOutcome.Unavailable -> SourceStatus.Unavailable(shown, reason)
    }
