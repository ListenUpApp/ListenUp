package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.dto.PreparedAudioFile
import com.calypsan.listenup.api.dto.PreparedPlayback as ContractPreparedPlayback
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.client.data.remote.PlaybackRpcFactory
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.test.fake.FakeProgressTracker
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

// Shared construction helpers for PlaybackManager jvmTests.
//
// [ProgressTracker] is `open` (W7 Phase E2.2.3 Task 2) so seam-level tests can
// substitute a hand-rolled [FakeProgressTracker] (see Testing rubric: "seam-level
// tests use fakes with in-memory state, not mocks"). Tests that need real
// session-state behaviour continue to use [buildProgressTracker] for a real
// instance whose interface dependencies are interface mocks.

/** Constructs a [ProgressTracker] whose dependencies are all interface mocks. */
fun buildProgressTracker(
    scope: CoroutineScope = CoroutineScope(Job()),
    positionRepository: PlaybackPositionRepository = defaultPositionRepository(),
): ProgressTracker =
    ProgressTracker(
        downloadRepository = mock<DownloadRepository>(),
        positionRepository = positionRepository,
        scope = scope,
    )

/**
 * Constructs a [FakeProgressTracker] whose dependencies are all interface mocks.
 * Mirrors [buildProgressTracker]; use when tests need to verify which tracker
 * methods were called rather than asserting on real session-state behaviour.
 */
fun buildFakeProgressTracker(
    scope: CoroutineScope = CoroutineScope(Job()),
    positionRepository: PlaybackPositionRepository = defaultPositionRepository(),
): FakeProgressTracker =
    FakeProgressTracker(
        downloadRepository = mock<DownloadRepository>(),
        positionRepository = positionRepository,
        scope = scope,
    )

/** Returns a [PlaybackPositionRepository] stub that returns success for all writes and null for reads. */
fun defaultPositionRepository(): PlaybackPositionRepository {
    val repo: PlaybackPositionRepository = mock()
    everySuspend { repo.savePlaybackState(any(), any()) } returns AppResult.Success(Unit)
    everySuspend { repo.get(any<BookId>()) } returns AppResult.Success(null)
    return repo
}

/**
 * Builds a [PlaybackRpcFactory] stub that returns a [ContractPreparedPlayback] with signed
 * streaming URLs for [audioFileIds]. Suitable for tests that exercise the streaming path
 * but do not need to assert on the specific signed-URL content.
 */
fun testPlaybackRpcFactory(
    vararg audioFileIds: String,
    bookId: String = "book-1",
): PlaybackRpcFactory {
    val service: PlaybackService =
        object : PlaybackService {
            override suspend fun prepare(bookId: BookId): AppResult<ContractPreparedPlayback> =
                AppResult.Success(
                    ContractPreparedPlayback(
                        bookId = bookId.value,
                        audioFiles =
                            audioFileIds.mapIndexed { idx, fileId ->
                                PreparedAudioFile(
                                    fileId = fileId,
                                    index = idx,
                                    url = "/api/v1/audio/${bookId.value}/$fileId?u=&exp=&sig=",
                                    format = "m4b",
                                    durationMs = 1_800_000L,
                                    sizeBytes = 45_000_000L,
                                )
                            },
                        resumePosition = null,
                    ),
                )

            override suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?> =
                AppResult.Failure(InternalError(debugInfo = "stub"))

            override suspend fun recordPosition(
                request: RecordPositionRequest,
            ): AppResult<PlaybackPositionSyncPayload> = AppResult.Failure(InternalError(debugInfo = "stub"))

            override suspend fun getStats(): AppResult<UserStatsSyncPayload?> =
                AppResult.Failure(InternalError(debugInfo = "stub"))

            override suspend fun recordListeningEvent(
                request: RecordListeningEventRequest,
            ): AppResult<ListeningEventSyncPayload> = AppResult.Failure(InternalError(debugInfo = "stub"))
        }
    return object : PlaybackRpcFactory {
        override suspend fun playbackService(): PlaybackService = service

        override suspend fun invalidate() = Unit
    }
}
