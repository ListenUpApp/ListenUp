package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.uploads.UploadFinalizeResult
import com.calypsan.listenup.api.dto.uploads.UploadSessionSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.UploadApiContract
import com.calypsan.listenup.client.domain.repository.UploadCandidate
import com.calypsan.listenup.client.domain.repository.UploadRepository
import com.calypsan.listenup.client.domain.repository.UploadStep
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * How many times one file may be sent before the session gives up on it.
 *
 * The session is the expensive thing: by file 480 of 500 there are tens of gigabytes staged, and
 * throwing all of it away over one dropped packet is a far worse answer than sending that file
 * again. Bounded, because retrying forever is its own failure mode — a server that will never
 * accept this file should be discovered in seconds, not never.
 */
internal const val MAX_FILE_ATTEMPTS: Int = 3

/** Grows between attempts so a brief outage isn't met with three requests inside a second. */
private val RETRY_BACKOFF = listOf(1.seconds, 4.seconds)

/**
 * Production [UploadRepository] — the session state machine over [UploadApiContract].
 *
 * The whole reason this is a repository rather than four calls at the UI is what happens when a
 * transfer dies halfway. A session that is neither finalized nor abandoned leaves a staging
 * directory of half-uploaded audio on the server, and nothing later cleans it up. So **every** exit
 * from the loop — a failed file, a failed finalize, a cancelled collector — abandons the session on
 * the way out, under [NonCancellable] so cancellation cannot skip the cleanup it triggered.
 *
 * Progress is byte-accurate across the whole session, not per file: [UploadStep.Staging.bytesSent]
 * carries completed files plus the live count of the file in flight, so a bar drawn over it moves
 * smoothly instead of snapping back to zero at every file boundary.
 *
 * [channelFlow] rather than `flow`: progress arrives on Ktor's `onUpload` callback, and emitting
 * from a callback into a plain `flow` builder risks violating the flow's single-coroutine
 * invariant. A channel is the sanctioned way to bridge that.
 */
internal class UploadRepositoryImpl(
    private val api: UploadApiContract,
) : UploadRepository {
    override fun upload(candidates: List<UploadCandidate>): Flow<UploadStep> =
        channelFlow {
            // Nothing selected is not a failure — it is zero books uploaded, which is exactly what
            // the server would have answered. No session is minted for it.
            if (candidates.isEmpty()) {
                send(UploadStep.Done(UploadFinalizeResult(books = emptyList())))
                return@channelFlow
            }

            val sessionId =
                when (val created = api.createSession()) {
                    is AppResult.Failure -> {
                        send(UploadStep.Failed(created.error))
                        return@channelFlow
                    }

                    is AppResult.Success -> {
                        created.data.sessionId
                    }
                }

            var settled = false
            try {
                settled = stageAndFinalize(sessionId, candidates)
            } finally {
                // Covers every exit including cancellation — which propagates untouched, since
                // there is no catch to swallow it. A finalize that ran has already removed the
                // staging directory server-side; only an unsettled session still owns one.
                if (!settled) withContext(NonCancellable) { abandonQuietly(sessionId) }
            }
        }

    /**
     * Streams every candidate, then finalizes. Returns true when the session reached a terminal
     * server-side state (finalize ran) and therefore needs no abandon.
     */
    private suspend fun ProducerScope<UploadStep>.stageAndFinalize(
        sessionId: String,
        candidates: List<UploadCandidate>,
    ): Boolean {
        // A single unknown size makes the whole total a lie: that file's real bytes still land in
        // `bytesSent`, so the bar climbs past the total, pins at 100% while data is still moving,
        // then snaps backwards at the file boundary. Unknown sizes are ordinary — cloud-backed SAF
        // providers return no size — so an honest indeterminate bar beats a confident wrong one.
        val totalBytes =
            if (candidates.any {
                    it.source.size == null
                }
            ) {
                0L
            } else {
                candidates.sumOf { it.source.size ?: 0L }
            }
        var completedBytes = 0L

        candidates.forEachIndexed { index, candidate ->
            send(
                UploadStep.Staging(
                    fileIndex = index,
                    fileCount = candidates.size,
                    filename = candidate.source.filename,
                    bytesSent = completedBytes,
                    totalBytes = totalBytes,
                ),
            )

            val uploaded =
                sendWithRetry(sessionId, candidate) { sent ->
                    send(
                        UploadStep.Staging(
                            fileIndex = index,
                            fileCount = candidates.size,
                            filename = candidate.source.filename,
                            // A retry restarts `sent` at zero, so progress rewinds to the start of
                            // this file rather than double-counting the bytes of the failed try.
                            bytesSent = completedBytes + sent,
                            totalBytes = totalBytes,
                        ),
                    )
                }

            when (uploaded) {
                is AppResult.Failure -> {
                    send(UploadStep.Failed(uploaded.error))
                    return false
                }

                is AppResult.Success -> {
                    completedBytes += candidate.source.size ?: 0L
                }
            }
        }

        send(UploadStep.Finalizing)
        return when (val finalized = api.finalize(sessionId)) {
            is AppResult.Failure -> {
                send(UploadStep.Failed(finalized.error))
                false
            }

            is AppResult.Success -> {
                send(UploadStep.Done(finalized.data))
                true
            }
        }
    }

    /**
     * Sends one file, retrying a *retryable* failure up to [MAX_FILE_ATTEMPTS] times.
     *
     * Honours the error's own contract: `isRetryable` exists to say "re-firing this exact call is
     * the right response", and nothing else in the stack acts on it for an upload — Ktor's
     * `HttpRequestRetry` covers idempotent methods only, and these are POSTs. A failure the server
     * calls non-retryable (a quota breach, a dead session) is returned immediately; sending it
     * again would only fail the same way.
     */
    private suspend fun sendWithRetry(
        sessionId: String,
        candidate: UploadCandidate,
        onProgress: suspend (Long) -> Unit,
    ): AppResult<UploadSessionSummary> {
        var attempt = 1
        while (true) {
            val result =
                api.uploadFile(
                    sessionId = sessionId,
                    relPath = candidate.relPath,
                    source = candidate.source,
                ) { sent, _ -> onProgress(sent) }
            if (result is AppResult.Success) return result

            val error = (result as AppResult.Failure).error
            if (!error.isRetryable || attempt >= MAX_FILE_ATTEMPTS) return result
            logger.info {
                "upload of ${candidate.relPath} failed (attempt $attempt/$MAX_FILE_ATTEMPTS): ${error.code} — retrying"
            }
            delay(RETRY_BACKOFF[(attempt - 1).coerceAtMost(RETRY_BACKOFF.lastIndex)])
            attempt++
        }
    }

    /** Best-effort staging cleanup. A failure here is logged, never surfaced — the caller already failed. */
    private suspend fun abandonQuietly(sessionId: String) {
        when (val abandoned = api.abandon(sessionId)) {
            is AppResult.Failure -> {
                logger.warn { "could not abandon upload session $sessionId: ${abandoned.error.debugInfo}" }
            }

            is AppResult.Success -> {
                Unit
            }
        }
    }
}
