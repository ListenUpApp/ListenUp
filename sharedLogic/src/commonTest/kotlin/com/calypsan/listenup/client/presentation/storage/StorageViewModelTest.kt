package com.calypsan.listenup.client.presentation.storage

import app.cash.turbine.test
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.repository.FakeDownloadRepository
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.download.StorageSpaceProvider
import com.calypsan.listenup.client.playback.PlaybackStateProvider
import dev.mokkery.verify.VerifyMode.Companion.not
import kotlinx.coroutines.flow.StateFlow
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.calypsan.listenup.core.error.ErrorBus

@OptIn(ExperimentalCoroutinesApi::class)
class StorageViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        // Local fake that overrides [observeDownloadedBooks] with a controllable [MutableStateFlow].
        // All other DownloadRepository methods are satisfied by [FakeDownloadRepository]'s defaults.
        class StorageViewModelFakeDownloadRepository(
            initial: List<DownloadedBookSummary> = emptyList(),
        ) : FakeDownloadRepository() {
            val downloads = MutableStateFlow(initial)

            override fun observeDownloadedBooks(): Flow<List<DownloadedBookSummary>> = downloads
        }

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        // Minimal in-memory PlaybackStateProvider (B9 guard): reports [playingBookId] as the
        // currently-playing book. Seam-level fake, not a mock (Testing rubric).
        class FakePlaybackStateProvider(
            playingBookId: BookId? = null,
        ) : PlaybackStateProvider {
            override val currentBookId: StateFlow<BookId?> = MutableStateFlow(playingBookId)

            override fun clearPlayback() = Unit
        }

        class Fixture(
            val downloadRepository: StorageViewModelFakeDownloadRepository,
            val downloadService: DownloadService,
            val storageSpaceProvider: StorageSpaceProvider,
        )

        fun buildVm(
            downloads: List<DownloadedBookSummary> = emptyList(),
            totalUsed: Long = 0L,
            available: Long = 1_000_000L,
            playingBookId: BookId? = null,
        ): Pair<StorageViewModel, Fixture> {
            val fixture =
                Fixture(
                    downloadRepository = StorageViewModelFakeDownloadRepository(downloads),
                    downloadService = mock(),
                    storageSpaceProvider = mock(),
                )
            // StorageSpaceProvider is an interface — safely mockable
            every { fixture.storageSpaceProvider.calculateStorageUsed() } returns totalUsed
            every { fixture.storageSpaceProvider.getAvailableSpace() } returns available
            val vm =
                StorageViewModel(
                    downloadRepository = fixture.downloadRepository,
                    downloadService = fixture.downloadService,
                    storageSpaceProvider = fixture.storageSpaceProvider,
                    errorBus = ErrorBus(),
                    playbackStateProvider = FakePlaybackStateProvider(playingBookId),
                )
            return vm to fixture
        }

        test("state reflects downloaded books from repository") {
            runTest {
                val summary =
                    DownloadedBookSummary(
                        bookId = "b1",
                        title = "Book One",
                        authorNames = "Author A",
                        coverBlurHash = null,
                        sizeBytes = 1_000L,
                        fileCount = 1,
                    )
                val (vm, _) = buildVm(downloads = listOf(summary), totalUsed = 1_000L, available = 500L)

                vm.state.test {
                    val first = awaitItem()
                    first.isLoading shouldBe true
                    val second = awaitItem()
                    second.isLoading shouldBe false
                    second.downloadedBooks shouldBe listOf(summary)
                    second.totalStorageUsed shouldBe 1_000L
                    second.availableStorage shouldBe 500L
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("confirmDeleteBook then executeDelete calls deleteDownload with matching bookId") {
            runTest {
                val summary =
                    DownloadedBookSummary(
                        bookId = "b1",
                        title = "B",
                        authorNames = "A",
                        coverBlurHash = null,
                        sizeBytes = 1L,
                        fileCount = 1,
                    )
                val (vm, fixture) = buildVm(downloads = listOf(summary))
                everySuspend { fixture.downloadService.deleteDownload(any()) } returns Unit

                vm.state.test {
                    skipItems(2)
                    vm.confirmDeleteBook(summary)
                    vm.executeDelete()
                    advanceUntilIdle()
                    verifySuspend { fixture.downloadService.deleteDownload(BookId("b1")) }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("executeDelete on the currently-playing book is refused and does not call deleteDownload (B9)") {
            runTest {
                val summary =
                    DownloadedBookSummary(
                        bookId = "b1",
                        title = "Now Playing Book",
                        authorNames = "A",
                        coverBlurHash = null,
                        sizeBytes = 1L,
                        fileCount = 1,
                    )
                // b1 is currently playing.
                val (vm, fixture) = buildVm(downloads = listOf(summary), playingBookId = BookId("b1"))
                everySuspend { fixture.downloadService.deleteDownload(any()) } returns Unit

                vm.state.test {
                    skipItems(2)
                    vm.confirmDeleteBook(summary)
                    vm.executeDelete()
                    advanceUntilIdle()
                    // Deletion is refused: the file:// sources of the active session are never unlinked.
                    verifySuspend(not) { fixture.downloadService.deleteDownload(any()) }
                    // The UI is told why.
                    val blocked = expectMostRecentItem()
                    blocked.blockedDeletionTitle shouldBe "Now Playing Book"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("executeDelete on a non-playing book proceeds even while a DIFFERENT book plays (B9)") {
            runTest {
                val summary =
                    DownloadedBookSummary(
                        bookId = "b1",
                        title = "B",
                        authorNames = "A",
                        coverBlurHash = null,
                        sizeBytes = 1L,
                        fileCount = 1,
                    )
                // A different book (b2) is playing → deleting b1 is safe.
                val (vm, fixture) = buildVm(downloads = listOf(summary), playingBookId = BookId("b2"))
                everySuspend { fixture.downloadService.deleteDownload(any()) } returns Unit

                vm.state.test {
                    skipItems(2)
                    vm.confirmDeleteBook(summary)
                    vm.executeDelete()
                    advanceUntilIdle()
                    verifySuspend { fixture.downloadService.deleteDownload(BookId("b1")) }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("confirmClearAll then executeDelete wipes all downloads in one sweep (reclaims orphans)") {
            runTest {
                val s1 = DownloadedBookSummary("b1", "B1", "A", null, 10L, 1)
                val s2 = DownloadedBookSummary("b2", "B2", "A", null, 20L, 1)
                val (vm, fixture) = buildVm(downloads = listOf(s1, s2))
                everySuspend { fixture.downloadService.deleteAllDownloads() } returns Unit

                vm.state.test {
                    skipItems(2)
                    vm.confirmClearAll()
                    vm.executeDelete()
                    advanceUntilIdle()
                    // Single sweep (files + rows), NOT a per-known-book loop — so orphaned downloads
                    // whose book left the library are reclaimed too.
                    verifySuspend { fixture.downloadService.deleteAllDownloads() }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
