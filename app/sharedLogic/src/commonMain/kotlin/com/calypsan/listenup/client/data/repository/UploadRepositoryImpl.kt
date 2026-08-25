package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.uploads.UploadFinalizeResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.UploadApiContract
import com.calypsan.listenup.client.domain.repository.UploadCandidate
import com.calypsan.listenup.client.domain.repository.UploadRepository
import com.calypsan.listenup.client.domain.repository.UploadStep
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

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
        val totalBytes = candidates.sumOf { it.source.size ?: 0L }
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
                api.uploadFile(
                    sessionId = sessionId,
                    relPath = candidate.relPath,
                    source = candidate.source,
                ) { sent, _ ->
                    send(
                        UploadStep.Staging(
                            fileIndex = index,
                            fileCount = candidates.size,
                            filename = candidate.source.filename,
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
