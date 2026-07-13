package com.calypsan.listenup.client.presentation.nowplaying

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.client.campfire.ActiveCampfireCoordinator
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.test.fake.FakeBookRepository
import com.calypsan.listenup.client.test.fake.FakeDownloadRepository
import com.calypsan.listenup.client.test.fake.FakePlaybackManager
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.core.Timestamp
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Now-playing teardown: when the currently-playing book leaves the local mirror (removed or
 * access-revoked), the player tears down — EXCEPT for the offline-grace case of a downloaded,
 * in-progress copy, which keeps playing (never-stranded).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingTeardownTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        val bookId = BookId("b1")

        fun sampleBook() =
            BookListItem(
                id = bookId,
                libraryId = LibraryId("lib"),
                folderId = FolderId("folder"),
                title = "Playing Book",
                authors = listOf(BookContributor(id = "a1", name = "Author", roles = listOf("Author"))),
                narrators = emptyList(),
                duration = 100_000L,
                coverPath = null,
                addedAt = Timestamp(epochMillis = 1L),
                updatedAt = Timestamp(epochMillis = 1L),
            )

        fun inProgressPosition() =
            PlaybackPosition(
                bookId = bookId.value,
                positionMs = 42_000L,
                playbackSpeed = 1.0f,
                hasCustomSpeed = false,
                updatedAtMs = 10L,
                syncedAtMs = null,
                lastPlayedAtMs = 10L,
                isFinished = false,
            )

        fun buildVm(
            bookRepository: FakeBookRepository,
            downloadRepository: FakeDownloadRepository,
            positionRepository: FakePlaybackPositionRepository,
            fakePm: FakePlaybackManager,
        ): NowPlayingViewModel {
            val playbackController: PlaybackController = mock()
            val playbackPreferences: PlaybackPreferences = mock()
            val networkMonitor: NetworkMonitor = mock()
            val documentRepository: DocumentRepository = mock()
            every { playbackController.acquire() } returns Unit
            every { playbackController.stop() } returns Unit
            every { playbackPreferences.observeDefaultPlaybackSpeed() } returns flowOf(1.0f)
            everySuspend { playbackPreferences.getDefaultPlaybackSpeed() } returns 1.0f
            every { documentRepository.observeDocuments(any()) } returns flowOf(emptyList())
            return NowPlayingViewModel(
                playbackManager = fakePm,
                bookRepository = bookRepository,
                sleepTimerManager = SleepTimerManager(CoroutineScope(Job())),
                playbackController = playbackController,
                playbackPreferences = playbackPreferences,
                networkMonitor = networkMonitor,
                documentRepository = documentRepository,
                downloadRepository = downloadRepository,
                playbackPositionRepository = positionRepository,
                activeCampfire = ActiveCampfireCoordinator(),
            )
        }

        test("streaming now-playing book that leaves the mirror tears down the player") {
            runTest {
                val bookRepo = FakeBookRepository(initialBooks = listOf(sampleBook()))
                val downloadRepo = FakeDownloadRepository() // NotDownloaded
                val positionRepo = FakePlaybackPositionRepository()
                val fakePm = FakePlaybackManager()
                fakePm.currentBookIdFlow.value = bookId

                buildVm(bookRepo, downloadRepo, positionRepo, fakePm)
                advanceUntilIdle()

                // Book is revoked/removed — drops out of the mirror.
                bookRepo.setBooks(emptyList())
                advanceUntilIdle()

                fakePm.clearPlaybackCalls shouldBe 1
            }
        }

        test("downloaded in-progress book that leaves the mirror keeps playing (offline grace)") {
            runTest {
                val bookRepo = FakeBookRepository(initialBooks = listOf(sampleBook()))
                val downloadRepo =
                    FakeDownloadRepository(
                        initialStatuses =
                            mapOf(bookId.value to BookDownloadStatus.Completed(bookId.value, totalBytes = 1_000L)),
                    )
                val positionRepo =
                    FakePlaybackPositionRepository(initialPositions = mapOf(bookId.value to inProgressPosition()))
                val fakePm = FakePlaybackManager()
                fakePm.currentBookIdFlow.value = bookId

                buildVm(bookRepo, downloadRepo, positionRepo, fakePm)
                advanceUntilIdle()

                bookRepo.setBooks(emptyList())
                advanceUntilIdle()

                fakePm.clearPlaybackCalls shouldBe 0
            }
        }

        test("downloaded but not-in-progress book that leaves the mirror drops") {
            runTest {
                val bookRepo = FakeBookRepository(initialBooks = listOf(sampleBook()))
                val downloadRepo =
                    FakeDownloadRepository(
                        initialStatuses =
                            mapOf(bookId.value to BookDownloadStatus.Completed(bookId.value, totalBytes = 1_000L)),
                    )
                // No saved position → not in progress.
                val positionRepo = FakePlaybackPositionRepository()
                val fakePm = FakePlaybackManager()
                fakePm.currentBookIdFlow.value = bookId

                buildVm(bookRepo, downloadRepo, positionRepo, fakePm)
                advanceUntilIdle()

                bookRepo.setBooks(emptyList())
                advanceUntilIdle()

                fakePm.clearPlaybackCalls shouldBe 1
            }
        }
    })
