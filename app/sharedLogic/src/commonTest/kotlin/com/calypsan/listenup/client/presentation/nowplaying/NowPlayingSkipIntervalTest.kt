package com.calypsan.listenup.client.presentation.nowplaying

import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.playback.TimelineFileInput
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.test.fake.FakeDownloadRepository
import com.calypsan.listenup.client.test.fake.FakePlaybackManager
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Pins the in-app transport skips to the user's configured intervals (#1300).
 *
 * ## The bug this exists to prevent
 *
 * `skipForward`/`skipBack` carried `= 30` / `= 10` default parameters and every Android call
 * site invoked them with no argument, so the default always won: the Settings sliders moved a
 * synced preference that the Android player never read. The setting worked on iOS only, because
 * iOS drives its own Swift coordinator and passes the value explicitly.
 *
 * The numbers below are deliberately *not* 30 and 10 — a test written around the stock defaults
 * would pass on exactly the defect it exists to catch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingSkipIntervalTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        /** A single 10-minute file, so book position and file position coincide. */
        fun timeline(): PlaybackTimeline =
            PlaybackTimeline.build(
                bookId = BookId("book1"),
                files = listOf(TimelineFileInput("af-1", "book.m4b", "m4b", 600_000L, 1L, null, "https://s/1")),
            )

        class Fixture(
            forwardSec: Int = PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC,
            backwardSec: Int = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC,
        ) {
            val forwardFlow = MutableStateFlow(forwardSec)
            val backwardFlow = MutableStateFlow(backwardSec)
            val fakePm = FakePlaybackManager()
            val seeks = mutableListOf<Long>()
            val playbackController: PlaybackController = mock()
            private val bookRepository: BookRepository = mock()
            private val playbackPreferences: PlaybackPreferences = mock()
            private val networkMonitor: NetworkMonitor = mock()
            private val documentRepository: DocumentRepository = mock()

            init {
                every { networkMonitor.isOnline() } returns true
                every { playbackPreferences.observeDefaultPlaybackSpeed() } returns flowOf(1.0f)
                every { playbackPreferences.observeDefaultVolumeBoostDb() } returns flowOf(0f)
                every { playbackPreferences.observeDefaultSkipForwardSec() } returns forwardFlow
                every { playbackPreferences.observeDefaultSkipBackwardSec() } returns backwardFlow
                everySuspend { bookRepository.getBookListItem(any()) } returns null
                every { bookRepository.observeIsBookLive(any()) } returns flowOf(true)
                every { documentRepository.observeDocuments(any()) } returns flowOf(emptyList())
                every { playbackController.seekTo(any()) } calls { (positionMs: Long) -> seeks += positionMs }

                fakePm.currentTimelineFlow.value = timeline()
                fakePm.totalDurationMsFlow.value = 600_000L
                fakePm.currentPositionMsFlow.value = 300_000L
            }

            val viewModel: NowPlayingViewModel =
                NowPlayingViewModel(
                    playbackManager = fakePm,
                    bookRepository = bookRepository,
                    sleepTimerManager = SleepTimerManager(CoroutineScope(Job())),
                    playbackController = playbackController,
                    playbackPreferences = playbackPreferences,
                    networkMonitor = networkMonitor,
                    documentRepository = documentRepository,
                    downloadRepository = FakeDownloadRepository(),
                    playbackPositionRepository = FakePlaybackPositionRepository(),
                    sheetState = NowPlayingSheetState(),
                    errorBus = ErrorBus(),
                )
        }

        test("skipForward moves by the configured interval, not the stock 30 seconds") {
            runTest(testDispatcher) {
                val fixture = Fixture(forwardSec = 45)
                advanceUntilIdle()

                fixture.viewModel.skipForward()

                fixture.seeks shouldBe listOf(345_000L)
            }
        }

        test("skipBack moves by the configured interval, not the stock 10 seconds") {
            runTest(testDispatcher) {
                val fixture = Fixture(backwardSec = 20)
                advanceUntilIdle()

                fixture.viewModel.skipBack()

                fixture.seeks shouldBe listOf(280_000L)
            }
        }

        test("the stock defaults still apply when the user has never touched the setting") {
            runTest(testDispatcher) {
                val fixture = Fixture()
                advanceUntilIdle()

                fixture.viewModel.skipForward()
                fixture.viewModel.skipBack()

                // 30 forward from 300_000, then 10 back from where that landed — the fake
                // republishes the landing position exactly as PlaybackManager does.
                fixture.seeks shouldBe listOf(330_000L, 320_000L)
            }
        }

        test("a setting changed mid-session is picked up on the very next skip") {
            runTest(testDispatcher) {
                val fixture = Fixture(forwardSec = 15)
                advanceUntilIdle()

                fixture.viewModel.skipForward()
                fixture.forwardFlow.value = 90
                advanceUntilIdle()
                fixture.viewModel.skipForward()

                fixture.seeks shouldBe listOf(315_000L, 405_000L)
            }
        }

        test("a forward skip still clamps to the end of the book") {
            runTest(testDispatcher) {
                val fixture = Fixture(forwardSec = 120)
                fixture.fakePm.currentPositionMsFlow.value = 599_000L
                advanceUntilIdle()

                fixture.viewModel.skipForward()

                fixture.seeks shouldBe listOf(600_000L)
            }
        }

        test("a backward skip still clamps to the start of the book") {
            runTest(testDispatcher) {
                val fixture = Fixture(backwardSec = 60)
                fixture.fakePm.currentPositionMsFlow.value = 5_000L
                advanceUntilIdle()

                fixture.viewModel.skipBack()

                fixture.seeks shouldBe listOf(0L)
            }
        }
    })
