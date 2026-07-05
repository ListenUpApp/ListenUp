@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.presentation.nowplaying

import app.cash.turbine.test
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager.ChapterInfo
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.test.fake.FakePlaybackManager
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

class NowPlayingProgressIsolationTest :
    FunSpec({
        beforeTest { Dispatchers.setMain(StandardTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        fun sampleBook(): BookListItem =
            BookListItem(
                id = BookId("b1"),
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = "Sample Book",
                authors = listOf(BookContributor(id = "author-1", name = "Test Author", roles = listOf("Author"))),
                narrators = listOf(BookContributor(id = "narrator-1", name = "Test Narrator", roles = listOf("Narrator"))),
                duration = 200_000L,
                coverPath = "/covers/sample.jpg",
                coverBlurHash = "L6PZfSi_.AyE_3t7t7R**0o#DgR4",
                addedAt = Timestamp(epochMillis = 1_704_067_200_000L),
                updatedAt = Timestamp(epochMillis = 1_704_067_200_000L),
            )

        fun newVm(fakePm: FakePlaybackManager): NowPlayingViewModel {
            val bookRepository = mock<BookRepository>()
            val playbackController = mock<PlaybackController>()
            val playbackPreferences = mock<PlaybackPreferences>()
            val networkMonitor = mock<NetworkMonitor>()
            val documentRepository = mock<DocumentRepository>()
            every { networkMonitor.isOnline() } returns true
            every { playbackPreferences.observeDefaultPlaybackSpeed() } returns flowOf(1.0f)
            everySuspend { playbackPreferences.getDefaultPlaybackSpeed() } returns 1.0f
            everySuspend { bookRepository.getBookListItem(any()) } returns sampleBook()
            every { bookRepository.observeIsBookLive(any()) } returns flowOf(true)
            every { documentRepository.observeDocuments(any()) } returns flowOf(emptyList())
            every { playbackController.acquire() } returns Unit
            return NowPlayingViewModel(
                playbackManager = fakePm,
                bookRepository = bookRepository,
                sleepTimerManager = SleepTimerManager(CoroutineScope(Job())),
                playbackController = playbackController,
                playbackPreferences = playbackPreferences,
                networkMonitor = networkMonitor,
                documentRepository = documentRepository,
                downloadRepository =
                    com.calypsan.listenup.client.test.fake
                        .FakeDownloadRepository(),
                playbackPositionRepository =
                    com.calypsan.listenup.client.test.fake
                        .FakePlaybackPositionRepository(),
            )
        }

        test("position-only update re-emits progress but not screenState; chapter change re-emits screenState") {
            runTest {
                val fakePm = FakePlaybackManager()
                fakePm.currentBookIdFlow.value = BookId("b1")
                fakePm.totalDurationMsFlow.value = 200_000L
                fakePm.currentChapterFlow.value =
                    ChapterInfo(
                        index = 0,
                        title = "One",
                        startMs = 0L,
                        endMs = 100_000L,
                        remainingMs = 100_000L,
                        totalChapters = 2,
                        isGenericTitle = false,
                    )
                val vm = newVm(fakePm)

                vm.screenState.test {
                    var state = awaitItem()
                    while (state.state !is NowPlayingState.Active) state = awaitItem()

                    fakePm.currentPositionMsFlow.value = 10_000L
                    fakePm.currentPositionMsFlow.value = 20_000L
                    fakePm.currentPositionMsFlow.value = 30_000L
                    expectNoEvents()

                    fakePm.currentChapterFlow.value =
                        ChapterInfo(
                            index = 1,
                            title = "Two",
                            startMs = 100_000L,
                            endMs = 200_000L,
                            remainingMs = 100_000L,
                            totalChapters = 2,
                            isGenericTitle = false,
                        )
                    val next = awaitItem().state
                    val active = next.shouldBeInstanceOf<NowPlayingState.Active>()
                    active.chapterIndex shouldBe 1
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("progress reflects each position update") {
            runTest {
                val fakePm = FakePlaybackManager()
                fakePm.currentBookIdFlow.value = BookId("b1")
                fakePm.totalDurationMsFlow.value = 200_000L
                val vm = newVm(fakePm)

                vm.progress.test {
                    awaitItem()
                    fakePm.currentPositionMsFlow.value = 50_000L
                    var p = awaitItem()
                    while (p.bookPositionMs != 50_000L) p = awaitItem()
                    p.bookProgress shouldBe 0.25f
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
