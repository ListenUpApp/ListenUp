package com.calypsan.listenup.client.presentation.storage

import app.cash.turbine.test
import com.calypsan.listenup.client.data.repository.FakeDownloadRepository
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.download.StorageSpaceProvider
import com.calypsan.listenup.client.playback.PlaybackStateProvider
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Pins that [StorageViewModel.state]'s blocking storage calculation
 * ([StorageSpaceProvider.calculateStorageUsed] / [StorageSpaceProvider.getAvailableSpace]) never
 * runs on the caller's dispatcher (`viewModelScope` is `Dispatchers.Main.immediate` in production)
 * — an audit finding: a full recursive filesystem walk with zero suspension points, run inline in
 * a `combine` feeding `stateIn(viewModelScope)`, is an ANR candidate that re-runs on every
 * `observeDownloadedBooks()` emission.
 *
 * Real threads, not virtual time: [com.calypsan.listenup.core.IODispatcher] on the JVM is
 * `Dispatchers.IO`, a genuine thread pool outside `TestCoroutineScheduler` — so this asserts on
 * `Thread.currentThread()` identity rather than `advanceUntilIdle()` sequencing. `StandardTestDispatcher`
 * (installed as Main) does not own a separate real thread — it pumps on the calling JVM thread —
 * so a pre-fix run (calculation inline on Main) reports the SAME thread as the test; a fixed run
 * (calculation on `Dispatchers.IO`) reports a genuinely different pool thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageViewModelDispatcherTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        class FakePlaybackStateProvider : PlaybackStateProvider {
            override val currentBookId: StateFlow<BookId?> = MutableStateFlow(null)

            override fun clearPlayback() = Unit
        }

        class RecordingStorageSpaceProvider : StorageSpaceProvider {
            @Volatile
            var calculateStorageUsedThread: Thread? = null

            @Volatile
            var getAvailableSpaceThread: Thread? = null

            override fun calculateStorageUsed(): Long {
                calculateStorageUsedThread = Thread.currentThread()
                return 100L
            }

            override fun getAvailableSpace(): Long {
                getAvailableSpaceThread = Thread.currentThread()
                return 200L
            }
        }

        class DownloadsFakeRepository : FakeDownloadRepository() {
            override fun observeDownloadedBooks(): Flow<List<DownloadedBookSummary>> = MutableStateFlow(emptyList())
        }

        test("calculateStorageUsed and getAvailableSpace run off the collecting (Main) thread") {
            runTest {
                val testThread = Thread.currentThread()
                val storageSpaceProvider = RecordingStorageSpaceProvider()
                val vm =
                    StorageViewModel(
                        downloadRepository = DownloadsFakeRepository(),
                        downloadService = mock<DownloadService>(),
                        storageSpaceProvider = storageSpaceProvider,
                        errorBus = ErrorBus(),
                        playbackStateProvider = FakePlaybackStateProvider(),
                    )

                vm.state.test {
                    awaitItem() // isLoading = true (pre-combine initial value)
                    awaitItem() // resolved state after the IO-confined calculation completes
                    cancelAndIgnoreRemainingEvents()
                }

                storageSpaceProvider.calculateStorageUsedThread shouldNotBe null
                storageSpaceProvider.calculateStorageUsedThread shouldNotBe testThread
                storageSpaceProvider.getAvailableSpaceThread shouldNotBe testThread
            }
        }
    })
