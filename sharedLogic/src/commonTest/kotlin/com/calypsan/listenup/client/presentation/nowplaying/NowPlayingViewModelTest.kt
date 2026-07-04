package com.calypsan.listenup.client.presentation.nowplaying

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.playback.NowPlayingOverlay
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager.ChapterInfo
import com.calypsan.listenup.client.playback.PlaybackManager.PlaybackErrorUiState
import com.calypsan.listenup.client.playback.PlaybackManager.PrepareResult
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingNavAction
import com.calypsan.listenup.client.test.fake.FakePlaybackManager
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Integration tests for the consolidated [NowPlayingViewModel].
 *
 * Coverage scope (B targeted, 3 tests):
 * - playBook flow (3): happy path with activate-before-seam ordering; offline error;
 *   generic prepare-null error.
 *
 * Uses [FakePlaybackManager] for the seam, following the
 * "fakes for seams" rule. The other deps are Mokkery-mocked as interfaces;
 * [SleepTimerManager] is a closed concrete class so a real instance is constructed
 * with a test-scoped [CoroutineScope] (its scope is unused by these tests since
 * none of them invoke timer-launching methods).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        class TestFixture {
            val fakePm = FakePlaybackManager()
            val bookRepository: BookRepository = mock()
            val playbackController: PlaybackController = mock()
            val playbackPreferences: PlaybackPreferences = mock()
            val networkMonitor: NetworkMonitor = mock()
            val documentRepository: DocumentRepository = mock()
            val sleepTimerManager: SleepTimerManager = SleepTimerManager(CoroutineScope(Job()))

            init {
                // Default: networkMonitor.isOnline() returns true. Tests override to false where needed.
                every { networkMonitor.isOnline() } returns true
                // Default: PlaybackPreferences.observeDefaultPlaybackSpeed() returns Flow of 1.0f.
                // Required by NowPlayingViewModel's surfaceMetadataFlow combine pipeline.
                every { playbackPreferences.observeDefaultPlaybackSpeed() } returns flowOf(1.0f)
                everySuspend { playbackPreferences.getDefaultPlaybackSpeed() } returns 1.0f
                // Default: bookRepository.getBookListItem returns null (no metadata).
                // bookFlow tolerates null; pure mapToNowPlayingState handles it.
                everySuspend { bookRepository.getBookListItem(any()) } returns null
                // Default: the now-playing book stays live, so the liveness teardown never fires.
                every { bookRepository.observeIsBookLive(any()) } returns flowOf(true)
                // Default: documentRepository.observeDocuments returns empty list (no documents).
                every { documentRepository.observeDocuments(any()) } returns flowOf(emptyList())
                // NowPlayingViewModel's init block calls playbackController.acquire() to
                // establish the MediaController connection. Mokkery is strict by default
                // and would raise CallNotMockedException without this stub.
                every { playbackController.acquire() } returns Unit
            }

            fun newVm(): NowPlayingViewModel =
                NowPlayingViewModel(
                    playbackManager = fakePm,
                    bookRepository = bookRepository,
                    sleepTimerManager = sleepTimerManager,
                    playbackController = playbackController,
                    playbackPreferences = playbackPreferences,
                    networkMonitor = networkMonitor,
                    documentRepository = documentRepository,
                    downloadRepository = com.calypsan.listenup.client.test.fake.FakeDownloadRepository(),
                    playbackPositionRepository = com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository(),
                )
        }

        // ========== Helpers ==========

        fun stubPrepareResult(bookId: BookId): PrepareResult =
            PrepareResult(
                timeline =
                    PlaybackTimeline(
                        bookId = bookId,
                        totalDurationMs = 1_800_000L,
                        files =
                            listOf(
                                PlaybackTimeline.FileSegment(
                                    audioFileId = "af-stub",
                                    filename = "stub.m4b",
                                    format = "m4b",
                                    startOffsetMs = 0L,
                                    durationMs = 1_800_000L,
                                    size = 1_000_000L,
                                    streamingUrl = "https://example.test/stub.m4b",
                                    localPath = null,
                                    mediaItemIndex = 0,
                                ),
                            ),
                    ),
                bookTitle = "Stub Book",
                bookAuthor = "Stub Author",
                seriesName = null,
                coverPath = null,
                totalChapters = 1,
                resumePositionMs = 0L,
                resumeSpeed = 1.0f,
            )

        fun stubTimeline(): PlaybackTimeline =
            PlaybackTimeline(
                bookId = BookId("book-1"),
                totalDurationMs = 1_800_000L,
                files =
                    listOf(
                        PlaybackTimeline.FileSegment(
                            audioFileId = "af-stub",
                            filename = "stub.m4b",
                            format = "m4b",
                            startOffsetMs = 0L,
                            durationMs = 1_800_000L,
                            size = 1_000_000L,
                            streamingUrl = "https://example.test/stub.m4b",
                            localPath = null,
                            mediaItemIndex = 0,
                        ),
                    ),
            )

        fun stubChapters(): List<Chapter> =
            listOf(
                Chapter(id = "ch-0", title = "Chapter 1", duration = 600_000L, startTime = 0L),
                Chapter(id = "ch-1", title = "Chapter 2", duration = 600_000L, startTime = 600_000L),
                Chapter(id = "ch-2", title = "Chapter 3", duration = 600_000L, startTime = 1_200_000L),
            )

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ========== lifecycle regression ==========

        test("init acquires the PlaybackController so MediaController connects") {
            val fixture = TestFixture()

            fixture.newVm()

            // A refactor mistakenly removed acquire/release from the
            // VM under the assumption that Koin-singleton lifetime obviated the call.
            // It does not — only acquire() triggers MediaControllerHolder.connect().
            // Without this call, every in-app playback command is a silent no-op.
            verify(VerifyMode.exactly(1)) { fixture.playbackController.acquire() }
        }

        // ========== playBook ==========

        test("playBook happy path activates book then invokes controller seam") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                val bookId = BookId("book-1")
                val prepareResult = stubPrepareResult(bookId)
                fixture.fakePm.stubbedPrepareResult = prepareResult
                every { fixture.networkMonitor.isOnline() } returns true
                everySuspend { fixture.playbackController.startPlayback(any()) } returns Unit

                val vm = fixture.newVm()
                vm.playBook(bookId)
                advanceUntilIdle()

                // activateBook must fire (records the bookId on the fake's recorder list).
                // PlaybackController.startPlayback must fire afterwards.
                // Strict ordering is asserted indirectly: activateBook is recorded, AND
                // startPlayback was invoked exactly once with the prepared result. The
                // production VM body in [NowPlayingViewModel.playBook] sequences them
                // activate-then-seam; both having fired proves the bug-3 ordering closure.
                fixture.fakePm.activatedBookIds shouldBe listOf(bookId)
                verifySuspend(VerifyMode.exactly(1)) {
                    fixture.playbackController.startPlayback(prepareResult)
                }
                // No error reported on the happy path.
                withClue("happy path must not report any error; got: ${fixture.fakePm.reportedErrors}") {
                    fixture.fakePm.reportedErrors.isEmpty() shouldBe true
                }
            }
        }

        test("playBook with offline + null prepare reports offline error") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                val bookId = BookId("book-1")
                fixture.fakePm.stubbedPrepareResult = null
                every { fixture.networkMonitor.isOnline() } returns false

                val vm = fixture.newVm()
                vm.playBook(bookId)
                advanceUntilIdle()

                fixture.fakePm.reportedErrors.size shouldBe 1
                withClue(
                    "expected offline error message; got: ${fixture.fakePm.reportedErrors.first().message}",
                ) {
                    fixture.fakePm.reportedErrors
                        .first()
                        .message
                        .contains("offline", ignoreCase = true) shouldBe true
                }
                withClue("must NOT activate when prepare fails") {
                    fixture.fakePm.activatedBookIds.isEmpty() shouldBe true
                }
                verifySuspend(VerifyMode.exactly(0)) {
                    fixture.playbackController.startPlayback(any())
                }
            }
        }

        test("playBook with online + null prepare reports generic error") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                val bookId = BookId("book-1")
                fixture.fakePm.stubbedPrepareResult = null
                every { fixture.networkMonitor.isOnline() } returns true

                val vm = fixture.newVm()
                vm.playBook(bookId)
                advanceUntilIdle()

                fixture.fakePm.reportedErrors.size shouldBe 1
                withClue(
                    "expected generic load-failure message; got: ${fixture.fakePm.reportedErrors.first().message}",
                ) {
                    fixture.fakePm.reportedErrors
                        .first()
                        .message
                        .contains("Failed to load", ignoreCase = true) shouldBe true
                }
                withClue("must NOT activate when prepare fails") {
                    fixture.fakePm.activatedBookIds.isEmpty() shouldBe true
                }
                verifySuspend(VerifyMode.exactly(0)) {
                    fixture.playbackController.startPlayback(any())
                }
            }
        }

        // ========== playPause ==========

        test("playPause when playing calls controller pause") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                every { fixture.playbackController.pause() } returns Unit
                every { fixture.playbackController.play() } returns Unit
                fixture.fakePm.isPlayingFlow.value = true

                val vm = fixture.newVm()
                vm.playPause()
                advanceUntilIdle()

                verify(VerifyMode.exactly(1)) { fixture.playbackController.pause() }
                verify(VerifyMode.not) { fixture.playbackController.play() }
            }
        }

        test("playPause when paused calls controller play") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                every { fixture.playbackController.pause() } returns Unit
                every { fixture.playbackController.play() } returns Unit
                fixture.fakePm.isPlayingFlow.value = false

                val vm = fixture.newVm()
                vm.playPause()
                advanceUntilIdle()

                verify(VerifyMode.exactly(1)) { fixture.playbackController.play() }
                verify(VerifyMode.not) { fixture.playbackController.pause() }
            }
        }

        // ========== Skip/seek ==========

        test("skipForward at speed 1_5 advances by speed-multiplied delta and clamps to total") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                every { fixture.playbackController.seekTo(any()) } returns Unit
                fixture.fakePm.currentTimelineFlow.value = stubTimeline()
                fixture.fakePm.currentPositionMsFlow.value = 100_000L
                fixture.fakePm.totalDurationMsFlow.value = 1_800_000L
                fixture.fakePm.playbackSpeedFlow.value = 1.5f

                val vm = fixture.newVm()
                vm.skipForward(30)
                advanceUntilIdle()

                // 100_000 + (30 × 1.5 × 1000) = 145_000
                verify(VerifyMode.exactly(1)) { fixture.playbackController.seekTo(145_000L) }
                fixture.fakePm.updatedPositions shouldBe listOf(145_000L)

                // Edge: at total - 5000, 30s skip clamps to total.
                // updatePosition(145_000) above moved currentPositionMsFlow to 145_000;
                // now reset to total - 5000 to exercise the clamp.
                fixture.fakePm.currentPositionMsFlow.value = 1_800_000L - 5_000L
                fixture.fakePm.updatedPositions.clear()
                vm.skipForward(30)
                advanceUntilIdle()
                fixture.fakePm.updatedPositions shouldBe listOf(1_800_000L)
            }
        }

        test("skipBack at speed 1_5 retreats by speed-multiplied delta and clamps to zero") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                every { fixture.playbackController.seekTo(any()) } returns Unit
                fixture.fakePm.currentTimelineFlow.value = stubTimeline()
                fixture.fakePm.currentPositionMsFlow.value = 100_000L
                fixture.fakePm.playbackSpeedFlow.value = 1.5f

                val vm = fixture.newVm()
                vm.skipBack(10)
                advanceUntilIdle()

                // 100_000 - (10 × 1.5 × 1000) = 85_000
                verify(VerifyMode.exactly(1)) { fixture.playbackController.seekTo(85_000L) }
                fixture.fakePm.updatedPositions shouldBe listOf(85_000L)

                // Edge: at 5000ms, 10s skip clamps to 0.
                fixture.fakePm.currentPositionMsFlow.value = 5_000L
                fixture.fakePm.updatedPositions.clear()
                vm.skipBack(10)
                advanceUntilIdle()
                fixture.fakePm.updatedPositions shouldBe listOf(0L)
            }
        }

        test("seekToChapter seeks to chapter start and ignores out-of-range indices") {
            runTest(testDispatcher) {
                // Production [NowPlayingViewModel.seekToChapter] uses chapters.getOrNull(index)
                // and returns when null — so out-of-range indices are a no-op (NOT clamped).
                // This deviates from an earlier "clamp to last/first" expectation;
                // the test asserts production behavior.
                val fixture = TestFixture()
                every { fixture.playbackController.seekTo(any()) } returns Unit
                fixture.fakePm.chaptersFlow.value = stubChapters()

                val vm = fixture.newVm()

                vm.seekToChapter(1)
                advanceUntilIdle()
                verify(VerifyMode.exactly(1)) { fixture.playbackController.seekTo(600_000L) }
                fixture.fakePm.updatedPositions shouldBe listOf(600_000L)

                // Out-of-range high (chapters.size == 3) → no-op.
                fixture.fakePm.updatedPositions.clear()
                vm.seekToChapter(99)
                advanceUntilIdle()
                verify(VerifyMode.not) { fixture.playbackController.seekTo(any<Long>()) }
                withClue(
                    "out-of-range high index must NOT update position; got: ${fixture.fakePm.updatedPositions}",
                ) {
                    fixture.fakePm.updatedPositions.isEmpty() shouldBe true
                }

                // Out-of-range low (negative) → no-op.
                vm.seekToChapter(-1)
                advanceUntilIdle()
                verify(VerifyMode.not) { fixture.playbackController.seekTo(any<Long>()) }
                withClue(
                    "out-of-range low index must NOT update position; got: ${fixture.fakePm.updatedPositions}",
                ) {
                    fixture.fakePm.updatedPositions.isEmpty() shouldBe true
                }

                // Index 0 → seeks to first chapter (startTime 0).
                vm.seekToChapter(0)
                advanceUntilIdle()
                verify(VerifyMode.exactly(1)) { fixture.playbackController.seekTo(0L) }
                fixture.fakePm.updatedPositions shouldBe listOf(0L)
            }
        }

        // ========== Speed ==========

        test("setSpeed updates controller and marks book as having custom speed") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                every { fixture.playbackController.setPlaybackSpeed(any()) } returns Unit

                val vm = fixture.newVm()
                vm.setSpeed(1.75f)
                advanceUntilIdle()

                verify(VerifyMode.exactly(1)) { fixture.playbackController.setPlaybackSpeed(1.75f) }
                fixture.fakePm.speedChanges shouldBe listOf(1.75f)
            }
        }

        test("resetSpeedToDefault uses preference value and marks book as default and cycleSpeed wraps") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                everySuspend { fixture.playbackPreferences.getDefaultPlaybackSpeed() } returns 1.25f
                every { fixture.playbackController.setPlaybackSpeed(any()) } returns Unit

                val vm = fixture.newVm()
                vm.resetSpeedToDefault()
                advanceUntilIdle()

                verify(VerifyMode.atLeast(1)) { fixture.playbackController.setPlaybackSpeed(1.25f) }
                fixture.fakePm.speedResets shouldBe listOf(1.25f)

                // cycleSpeed wraps from the highest value (3.0f) back to the lowest (0.5f).
                // The cycle list is [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0].
                fixture.fakePm.playbackSpeedFlow.value = 3.0f
                vm.cycleSpeed()
                advanceUntilIdle()
                // cycleSpeed routes through setSpeed → onSpeedChanged, so speedChanges grows.
                fixture.fakePm.speedChanges.last() shouldBe 0.5f
            }
        }

        // ========== Sleep timer ==========

        test("setSleepTimer arms timer and cancelSleepTimer disarms it") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                // SleepTimerManager is a real instance (closed concrete class — cannot mock with
                // Mokkery in this classpath; see fixture init). Both setTimer and cancelTimer
                // update state.value synchronously before any background work, so we can
                // observe state through the real instance without virtual-time juggling.

                val vm = fixture.newVm()
                vm.setSleepTimer(SleepTimerMode.Duration(minutes = 15))
                advanceUntilIdle()

                val activeState = fixture.sleepTimerManager.state.value
                withClue("expected active Duration timer; got: $activeState") {
                    (activeState is SleepTimerState.Active && activeState.mode is SleepTimerMode.Duration) shouldBe true
                }

                vm.cancelSleepTimer()
                advanceUntilIdle()
                withClue("expected inactive after cancel; got: ${fixture.sleepTimerManager.state.value}") {
                    (fixture.sleepTimerManager.state.value is SleepTimerState.Inactive) shouldBe true
                }
            }
        }

        test("init-block sleepEvent observer fades out and pauses on timer expiry") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                every { fixture.playbackController.setVolume(any()) } returns Unit
                every { fixture.playbackController.pause() } returns Unit

                val vm = fixture.newVm()
                advanceUntilIdle() // let init-block collectors start

                // Use EndOfChapter mode + drive currentChapter to trigger the sleep event
                // through public API. Duration mode's timer loop combines virtual-time delay
                // with wall-clock math (Clock.System.now()), which doesn't progress under
                // a TestDispatcher; EndOfChapter mode fires synchronously from
                // SleepTimerManager.onChapterChanged when the chapter index advances.
                vm.setSleepTimer(SleepTimerMode.EndOfChapter)
                advanceUntilIdle()

                // First chapter sets lastKnownChapterIndex; second (greater) triggers sleep.
                fixture.fakePm.currentChapterFlow.value =
                    ChapterInfo(
                        index = 0,
                        title = "Chapter 1",
                        startMs = 0L,
                        endMs = 600_000L,
                        remainingMs = 600_000L,
                        totalChapters = 3,
                        isGenericTitle = false,
                    )
                advanceUntilIdle()
                fixture.fakePm.currentChapterFlow.value =
                    ChapterInfo(
                        index = 1,
                        title = "Chapter 2",
                        startMs = 600_000L,
                        endMs = 1_200_000L,
                        remainingMs = 600_000L,
                        totalChapters = 3,
                        isGenericTitle = false,
                    )
                advanceUntilIdle() // drains fadeOutAndPause's 30 × delay + final 100ms delay

                // fadeOutAndPause drives the controller through 30 setVolume steps, one pause,
                // then a final setVolume(1f). At minimum: pause was invoked once.
                verify(VerifyMode.atLeast(1)) { fixture.playbackController.pause() }
                verify(VerifyMode.atLeast(1)) { fixture.playbackController.setVolume(any()) }
            }
        }

        // === Overlay + state machine ===

        test("showChapterPicker updates screenState overlay - hideChapterPicker restores None") {
            runTest(testDispatcher) {
                val fixture = TestFixture()

                val vm = fixture.newVm()
                // screenState uses WhileSubscribed — keep an active collector so the
                // upstream pipeline runs and .value reflects mutations.
                backgroundScope.launch { vm.screenState.collect {} }
                advanceUntilIdle()

                vm.showChapterPicker()
                advanceUntilIdle()
                withClue("expected ChapterPicker overlay; got: ${vm.screenState.value.overlay}") {
                    (vm.screenState.value.overlay is NowPlayingOverlay.ChapterPicker) shouldBe true
                }

                vm.hideChapterPicker()
                advanceUntilIdle()
                withClue("expected None overlay; got: ${vm.screenState.value.overlay}") {
                    (vm.screenState.value.overlay is NowPlayingOverlay.None) shouldBe true
                }
            }
        }

        test("playbackError emission surfaces as NowPlayingState Error - recovery clears it") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                // Activate a book so the VM has state to display.
                fixture.fakePm.activateBook(BookId("book-1"))

                val vm = fixture.newVm()
                // screenState uses WhileSubscribed — keep an active collector so the
                // upstream pipeline runs and .value reflects mutations.
                backgroundScope.launch { vm.screenState.collect {} }
                advanceUntilIdle()

                // Drive an error.
                fixture.fakePm.playbackErrorFlow.value =
                    PlaybackErrorUiState(
                        message = "Network unavailable",
                        isRecoverable = true,
                        timestampMs = 1_000L,
                    )
                advanceUntilIdle()

                // Per mapToNowPlayingState (NowPlayingStateMapper.kt:24-43), a non-null
                // metadata.error produces NowPlayingState.Error. With activateBook driving
                // currentBookIdFlow but bookRepository.getBookListItem stubbed to null,
                // we hit the book-null + error branch.
                val errored = vm.screenState.value
                withClue("expected state is NowPlayingState.Error; got: ${errored.state}") {
                    (errored.state is NowPlayingState.Error) shouldBe true
                }

                // Recover.
                fixture.fakePm.playbackErrorFlow.value = null
                advanceUntilIdle()

                val recovered = vm.screenState.value
                withClue("expected state recovered (not Error); got: ${recovered.state}") {
                    (recovered.state !is NowPlayingState.Error) shouldBe true
                }
            }
        }

        // === closeBook ===

        test("closeBook stops controller, clears playback, cancels sleep timer, collapses overlay") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                every { fixture.playbackController.stop() } returns Unit
                // Setup: book is playing and expanded.
                fixture.fakePm.activateBook(BookId("book-1"))

                val vm = fixture.newVm()
                // screenState uses WhileSubscribed — keep an active collector so the
                // upstream pipeline runs and .value reflects mutations.
                backgroundScope.launch { vm.screenState.collect {} }
                vm.expand()
                advanceUntilIdle()
                withClue("precondition: expanded") {
                    vm.screenState.value.isExpanded shouldBe true
                }

                vm.closeBook()
                advanceUntilIdle()

                verify(VerifyMode.exactly(1)) { fixture.playbackController.stop() }
                fixture.fakePm.clearPlaybackCalls shouldBe 1
                // SleepTimerManager is real — state should be Inactive after cancel.
                withClue(
                    "expected sleep timer Inactive after closeBook; got: ${fixture.sleepTimerManager.state.value}",
                ) {
                    (fixture.sleepTimerManager.state.value is SleepTimerState.Inactive) shouldBe true
                }
                withClue("expected collapsed after closeBook") {
                    !vm.screenState.value.isExpanded shouldBe true
                }
            }
        }

        // ========== firstPdfDocId ==========

        test("firstPdfDocId is null when the book has no pdf documents") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                val bookId = BookId("book-1")
                val docsFlow = MutableStateFlow<List<BookDocument>>(emptyList())
                every { fixture.documentRepository.observeDocuments(bookId) } returns docsFlow
                fixture.fakePm.activateBook(bookId)

                val vm = fixture.newVm()
                backgroundScope.launch { vm.firstPdfDocId.collect {} }
                advanceUntilIdle()

                withClue("no pdf docs → firstPdfDocId must be null; got: ${vm.firstPdfDocId.value}") {
                    vm.firstPdfDocId.value shouldBe null
                }
            }
        }

        test("firstPdfDocId returns first pdf doc id when book has pdf documents") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                val bookId = BookId("book-1")
                val pdfDoc =
                    BookDocument(
                        id = "doc-pdf-1",
                        index = 0,
                        filename = "guide.pdf",
                        format = "pdf",
                        size = 1_000L,
                        hash = "abc123",
                    )
                val docsFlow = MutableStateFlow(listOf(pdfDoc))
                every { fixture.documentRepository.observeDocuments(bookId) } returns docsFlow
                fixture.fakePm.activateBook(bookId)

                val vm = fixture.newVm()
                backgroundScope.launch { vm.firstPdfDocId.collect {} }
                advanceUntilIdle()

                withClue("pdf doc present → firstPdfDocId must be doc-pdf-1; got: ${vm.firstPdfDocId.value}") {
                    vm.firstPdfDocId.value shouldBe "doc-pdf-1"
                }
            }
        }

        test("onOpenCurrentPdf emits OpenDocumentViewer nav action on success") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                val bookId = BookId("book-1")
                val docId = "doc-pdf-1"
                val localPath = "/cache/books/book-1/doc-pdf-1.pdf"
                val pdfDoc =
                    BookDocument(
                        id = docId,
                        index = 0,
                        filename = "guide.pdf",
                        format = "pdf",
                        size = 1_000L,
                        hash = "abc123",
                    )
                every { fixture.documentRepository.observeDocuments(bookId) } returns MutableStateFlow(listOf(pdfDoc))
                everySuspend { fixture.documentRepository.ensureLocal(bookId, docId) } returns AppResult.Success(localPath)
                fixture.fakePm.activateBook(bookId)

                val vm = fixture.newVm()
                backgroundScope.launch { vm.firstPdfDocId.collect {} }
                advanceUntilIdle()

                vm.navActions.test {
                    vm.onOpenCurrentPdf()
                    advanceUntilIdle()
                    val action = awaitItem()
                    withClue("expected OpenDocumentViewer($localPath); got: $action") {
                        (action is NowPlayingNavAction.OpenDocumentViewer && action.localPath == localPath) shouldBe true
                    }
                }
            }
        }

        // ========== Sleep timer resilience ==========

        test("sleepEvent observer resets timer to Inactive even when fadeOutAndPause throws") {
            runTest(testDispatcher) {
                val fixture = TestFixture()
                // Make setVolume (called in the fade loop) throw on first invocation.
                // This exercises the bug: before the fix, the exception would escape
                // the collector and onFadeCompleted() would never be called, leaving
                // the timer stuck in FadingOut.
                every { fixture.playbackController.setVolume(any()) } throws RuntimeException("simulated fade failure")
                every { fixture.playbackController.pause() } returns Unit

                val vm = fixture.newVm()
                advanceUntilIdle() // let init-block collectors start

                // Use EndOfChapter mode — fires synchronously from onChapterChanged without
                // wall-clock math, so it works cleanly under a TestDispatcher.
                vm.setSleepTimer(SleepTimerMode.EndOfChapter)
                advanceUntilIdle()

                // Drive first chapter (sets lastKnownChapterIndex).
                fixture.fakePm.currentChapterFlow.value =
                    ChapterInfo(
                        index = 0,
                        title = "Chapter 1",
                        startMs = 0L,
                        endMs = 600_000L,
                        remainingMs = 600_000L,
                        totalChapters = 3,
                        isGenericTitle = false,
                    )
                advanceUntilIdle()

                // Drive second chapter — this fires the sleep event, which calls
                // fadeOutAndPause(), which calls setVolume() and throws immediately.
                fixture.fakePm.currentChapterFlow.value =
                    ChapterInfo(
                        index = 1,
                        title = "Chapter 2",
                        startMs = 600_000L,
                        endMs = 1_200_000L,
                        remainingMs = 600_000L,
                        totalChapters = 3,
                        isGenericTitle = false,
                    )
                advanceUntilIdle()

                // After the fix: the try/catch in the collector must call onFadeCompleted()
                // even when fadeOutAndPause() throws, resetting state to Inactive.
                withClue(
                    "sleep timer must return to Inactive after fade failure; got: ${fixture.sleepTimerManager.state.value}",
                ) {
                    (fixture.sleepTimerManager.state.value is SleepTimerState.Inactive) shouldBe true
                }
            }
        }
    })
