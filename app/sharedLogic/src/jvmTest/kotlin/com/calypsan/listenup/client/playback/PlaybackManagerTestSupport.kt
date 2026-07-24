package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.PreparedAudioFile
import com.calypsan.listenup.api.dto.PreparedPlayback as ContractPreparedPlayback
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.test.fake.FakeProgressTracker
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

// Shared construction helpers for PlaybackManager jvmTests.
//
// [ProgressTracker] is `open` so seam-level tests can
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
 * Builds a [PlaybackPrepareRepository] stub that returns a [ContractPreparedPlayback] with signed
 * streaming URLs for [audioFileIds]. Suitable for tests that exercise the streaming path but do not
 * need to assert on the specific signed-URL content.
 */
fun testPlaybackPrepareRepository(
    vararg audioFileIds: String,
    bookId: String = "book-1",
): PlaybackPrepareRepository =
    object : PlaybackPrepareRepository {
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

        override suspend fun getPosition(bookId: BookId) = AppResult.Success(null)
    }
