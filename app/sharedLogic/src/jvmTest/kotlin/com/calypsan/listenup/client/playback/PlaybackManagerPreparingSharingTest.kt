package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakePlaybackBandwidthCoordinator
import com.calypsan.listenup.api.result.AppResult
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Regression coverage for [PlaybackManager.preparingBookIdUi]'s wiring inside [PlaybackManagerImpl].
 *
 * `preparingBookIdUi` used to be `.stateIn(scope, SharingStarted.Eagerly, null)`. That call — for
 * ANY `SharingStarted` mode, not just `Eagerly` — launches a sharing coroutine into `scope`
 * immediately, at property-initialization time; the mode only controls WHEN that coroutine starts
 * *collecting*, not whether it exists. That coroutine never completes, so every `PlaybackManagerImpl`
 * ever constructed got a permanent, unfinishable child of `scope` — including test-only instances
 * that never touch this flow at all. jvmTests that bind `scope` to their `TestScope` (the established
 * `createPlaybackManagerWithScope` pattern in `PlaybackManagerBoostTest` / `PlaybackManagerSpeedTest`
 * / `PlaybackManagerPositionTransitionTest`, needed so `advanceUntilIdle` can drain
 * `ProgressTracker`/`PlaybackManager` coroutines) then failed with `UncompletedCoroutinesError` — even
 * though none of those tests ever touch [PlaybackManager.preparingBookIdUi].
 *
 * The fix is architectural, not a `SharingStarted` tweak: [PlaybackManagerImpl.preparingBookIdUi] is
 * a plain `Flow`, with NO `.stateIn` call anywhere inside `PlaybackManagerImpl` — per the rubric,
 * turning it into hot state is the consumer's job ([NowPlayingViewModel] already does this via its
 * own `combine(...).stateIn(viewModelScope, ...)`). [test 1] below reproduces the exact failure shape
 * and is the regression guard: constructing a manager bound to the test's own `TestScope` and never
 * subscribing to `preparingBookIdUi` must complete cleanly.
 *
 * The DEBOUNCE-semantics behaviour (a fresh collector re-running its own window rather than
 * inheriting an already-elapsed one — the accepted consequence of `preparingBookIdUi` being a cold,
 * unshared `Flow`) is unit-tested directly against the pure [preparingBookIdUiFlow] function in
 * `PreparingBookIdUiFlowTest`, which needs no `PlaybackManagerImpl` scaffolding — kept there instead
 * of duplicated here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackManagerPreparingSharingTest :
    FunSpec({
        // Binds the manager's CoroutineScope to the enclosing TestScope — mirrors
        // PlaybackManagerBoostTest's createPlaybackManagerWithScope, the pattern actually used by
        // the suites that regressed under the old .stateIn(scope, ...) wiring. A plain,
        // test-independent CoroutineScope(Job()) would NOT reproduce the bug: it runs on
        // Dispatchers.Default, entirely decoupled from runTest's coroutine tracking.
        fun TestScope.createPlaybackManagerBoundToTestScope(db: ListenUpDatabase): PlaybackManager {
            val scope = CoroutineScope(coroutineContext)

            val tokenProvider: AudioTokenProvider = mock()
            everySuspend { tokenProvider.prepareForPlayback() } returns Unit

            val serverConfig: ServerConfig = mock()

            val imageStorage: ImageStorage = mock()
            every { imageStorage.exists(any()) } returns false

            val downloadService: DownloadService = mock()
            everySuspend { downloadService.getLocalPath(any()) } returns null
            everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
            everySuspend { downloadService.downloadBook(any()) } returns AppResult.Success(DownloadOutcome.AlreadyDownloaded)

            val playbackPreferences: PlaybackPreferences = mock()
            everySuspend { playbackPreferences.getDefaultPlaybackSpeed() } returns 1.0f
            everySuspend { playbackPreferences.getDefaultVolumeBoostDb() } returns 0.0f

            val progressTracker = buildProgressTracker(scope = scope)

            val localPreferences: LocalPreferences = mock()
            every { localPreferences.autoRewindEnabled } returns MutableStateFlow(false)
            return PlaybackManagerImpl(
                serverConfig = serverConfig,
                playbackPreferences = playbackPreferences,
                bookDao = db.bookDao(),
                audioFileDao = db.audioFileDao(),
                chapterDao = db.chapterDao(),
                imageStorage = imageStorage,
                progressTracker = progressTracker,
                reporter =
                    PlaybackProgressReporter(
                        progressTracker,
                        recorder = null,
                        scope = scope,
                        localPreferences = localPreferences,
                    ),
                tokenProvider = tokenProvider,
                deviceContext = DeviceContext(type = DeviceType.Phone),
                downloadService = downloadService,
                prepareRepository = testPlaybackPrepareRepository("af-0"),
                channel = RpcChannel.forTest(mock<BookService>()),
                scope = scope,
                bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(),
                localPreferences = localPreferences,
                playbackBandwidthCoordinator = FakePlaybackBandwidthCoordinator(),
            )
        }

        test("constructing PlaybackManagerImpl inside runTest leaves no dangling collector when nothing subscribes to preparingBookIdUi") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    // Regression shape: build a real manager bound to this TestScope and never touch
                    // preparingBookIdUi — mirrors PlaybackManagerBoostTest/SpeedTest/
                    // PositionTransitionTest, all of which failed with UncompletedCoroutinesError
                    // under the old .stateIn(scope, ...) wiring, for ANY SharingStarted mode.
                    createPlaybackManagerBoundToTestScope(db)
                    advanceUntilIdle()
                    // No assertion needed beyond "the test completes" — runTest itself fails with
                    // UncompletedCoroutinesError if a .stateIn(scope, ...) regresses back in.
                }
            } finally {
                db.close()
            }
        }
    })
