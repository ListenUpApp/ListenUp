package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.ListeningEventRepository
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
): ProgressTracker {
    val stubSyncApi = mock<SyncApiContract>()
    return ProgressTracker(
        downloadRepository = mock<DownloadRepository>(),
        listeningEventRepository = mock<ListeningEventRepository>(),
        syncApi = stubSyncApi,
        positionRepository = positionRepository,
        scope = scope,
    )
}

/**
 * Constructs a [FakeProgressTracker] whose dependencies are all interface mocks.
 * Mirrors [buildProgressTracker]; use when tests need to verify which tracker
 * methods were called rather than asserting on real session-state behaviour.
 */
fun buildFakeProgressTracker(
    scope: CoroutineScope = CoroutineScope(Job()),
    positionRepository: PlaybackPositionRepository = defaultPositionRepository(),
): FakeProgressTracker {
    val stubSyncApi = mock<SyncApiContract>()
    return FakeProgressTracker(
        downloadRepository = mock<DownloadRepository>(),
        listeningEventRepository = mock<ListeningEventRepository>(),
        syncApi = stubSyncApi,
        positionRepository = positionRepository,
        scope = scope,
    )
}

/** Returns a [PlaybackPositionRepository] stub that returns success for all writes and null for reads. */
fun defaultPositionRepository(): PlaybackPositionRepository {
    val repo: PlaybackPositionRepository = mock()
    everySuspend { repo.savePlaybackState(any(), any()) } returns AppResult.Success(Unit)
    everySuspend { repo.getEntity(any<BookId>()) } returns AppResult.Success(null)
    return repo
}
