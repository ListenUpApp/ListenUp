package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.client.data.remote.DEFAULT_RPC_TIMEOUT
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.RpcDispatch
import com.calypsan.listenup.client.data.remote.RpcPolicy
import com.calypsan.listenup.core.BookId
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The resume-position fetch sits between a listener tapping play and hearing anything, so its
 * timeout is a latency budget, not a reliability knob.
 *
 * On 2026-08-07 it ran on the channel default (15s) AND declared itself idempotent, so a half-open
 * socket cost 15s plus a 15s retry — **30 seconds of silence before a fully-downloaded book began
 * to play**. Its own KDoc claimed it "never blocks or fails playback"; it was an awaited suspend
 * call in the middle of the critical path.
 *
 * It is now bounded short and never retried. A healthy socket answers in ~20ms so the resume point
 * is still server-correct; a degraded one gives up quickly and resumes from the local Room row.
 * Losing the reconcile is survivable — starting playback no longer claims position authority
 * (`PlaybackStartedAuthorityTest`), so a stale local row can no longer discard another device's
 * progress. Waiting was the only unsurvivable option.
 */
class ResumePositionFetchBoundTest :
    FunSpec({

        test("getPosition is bounded well under a second and never retries") {
            runTest {
                val dispatch = RecordingDispatch<PlaybackService>(NoOpPlaybackService)
                val repo = PlaybackPrepareRepositoryImpl(RpcChannel(dispatch, RpcPolicy.Authed))

                repo.getPosition(BookId("book-1"))

                val bound = dispatch.lastTimeout
                withClue("resume fetch must stay inside the playback-start budget") {
                    (bound != null && bound <= 1.seconds) shouldBe true
                }
                // idempotent=true would license RpcProxyCache to re-fire on a timeout, doubling the
                // bound. For a read whose failure degrades to the local row, one attempt is enough.
                dispatch.lastIdempotent shouldBe false
            }
        }

        test("prepare keeps the full default bound — its result is required, not optional") {
            runTest {
                val dispatch = RecordingDispatch<PlaybackService>(NoOpPlaybackService)
                val repo = PlaybackPrepareRepositoryImpl(RpcChannel(dispatch, RpcPolicy.Authed))

                repo.prepare(BookId("book-1"))

                // Unlike getPosition, prepare() returns the signed streaming URLs. There is no local
                // fallback for a book that is not downloaded, so cutting it short would strand it.
                dispatch.lastTimeout shouldBe DEFAULT_RPC_TIMEOUT
            }
        }
    })

/** Records the dispatch policy each call was issued under, then delegates to the service. */
private class RecordingDispatch<S : Any>(
    private val service: S,
) : RpcDispatch<S> {
    var lastTimeout: Duration? = null
    var lastIdempotent: Boolean? = null

    override suspend fun <R> call(
        timeout: Duration,
        idempotent: Boolean,
        block: suspend (S) -> R,
    ): R {
        lastTimeout = timeout
        lastIdempotent = idempotent
        return block(service)
    }

    override fun <R> streaming(subscribe: suspend (S) -> Flow<R>): Flow<R> = emptyFlow()

    override suspend fun invalidate() = Unit
}

/** Minimal [PlaybackService] stand-in — the tests assert on dispatch policy, not on payloads. */
private object NoOpPlaybackService : PlaybackService {
    override suspend fun prepare(bookId: BookId): AppResult<PreparedPlayback> =
        AppResult.Success(PreparedPlayback(bookId = bookId.value, audioFiles = emptyList(), resumePosition = null))

    override suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?> = AppResult.Success(null)

    override suspend fun recordPosition(request: RecordPositionRequest): AppResult<PlaybackPositionSyncPayload> =
        throw NotImplementedError()

    override suspend fun getStats(): AppResult<UserStatsSyncPayload?> = throw NotImplementedError()

    override suspend fun recordListeningEvent(
        request: RecordListeningEventRequest,
    ): AppResult<ListeningEventSyncPayload> = throw NotImplementedError()
}
