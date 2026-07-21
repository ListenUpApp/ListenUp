package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

class DefaultBookAvailabilityTest :
    FunSpec({

        val testBookId = BookId("book-1")

        // ========== Fixture helpers ==========

        fun buildAvailability(
            downloadStatus: BookDownloadStatus = BookDownloadStatus.NotDownloaded("book-1"),
            reachability: Reachability = Reachability.Unknown,
            unmetered: Boolean = true,
            wifiOnly: Boolean = false,
            playbackAvailable: Boolean = true,
        ): DefaultBookAvailability {
            val downloadRepository: DownloadRepository = mock()
            val localPreferences: LocalPreferences = mock()
            every { downloadRepository.observeBookStatus(testBookId) } returns MutableStateFlow(downloadStatus)
            every { localPreferences.wifiOnlyDownloads } returns MutableStateFlow(wifiOnly)

            val serverReachability =
                object : ServerReachability {
                    override val state: StateFlow<Reachability> = MutableStateFlow(reachability)

                    override suspend fun retry() = Unit
                }
            val networkMonitor =
                object : NetworkMonitor {
                    override fun isOnline(): Boolean = true

                    override val isOnlineFlow: StateFlow<Boolean> = MutableStateFlow(true)
                    override val isOnUnmeteredNetworkFlow: StateFlow<Boolean> = MutableStateFlow(unmetered)
                }

            return DefaultBookAvailability(
                downloadRepository = downloadRepository,
                serverReachability = serverReachability,
                networkMonitor = networkMonitor,
                localPreferences = localPreferences,
                playbackAvailable = playbackAvailable,
            )
        }

        // ========== Availability matrix tests ==========

        test("unreachable + completed download: everything enabled, no warning") {
            runTest {
                val availability =
                    buildAvailability(
                        downloadStatus = BookDownloadStatus.Completed(bookId = "book-1", totalBytes = 1024L),
                        reachability = Reachability.Unreachable,
                    )
                availability.observe(testBookId).test {
                    val state = awaitItem()
                    state.canPlay shouldBe true
                    state.canDownload shouldBe true
                    state.showServerWarning shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("unreachable + not downloaded: attempt-first — play/download enabled, warning hint shown") {
            runTest {
                val availability =
                    buildAvailability(
                        downloadStatus = BookDownloadStatus.NotDownloaded(bookId = "book-1"),
                        reachability = Reachability.Unreachable,
                    )
                availability.observe(testBookId).test {
                    val state = awaitItem()
                    state.canPlay shouldBe true
                    state.canDownload shouldBe true
                    state.showServerWarning shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("reachable + not downloaded: canPlay=true, canDownload=true, showServerWarning=false") {
            runTest {
                val availability =
                    buildAvailability(
                        downloadStatus = BookDownloadStatus.NotDownloaded(bookId = "book-1"),
                        reachability = Reachability.Reachable,
                    )
                availability.observe(testBookId).test {
                    val state = awaitItem()
                    state.canPlay shouldBe true
                    state.canDownload shouldBe true
                    state.showServerWarning shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("unknown reachability + not downloaded: attempt-first — play/download enabled, no warning") {
            runTest {
                val availability =
                    buildAvailability(
                        downloadStatus = BookDownloadStatus.NotDownloaded(bookId = "book-1"),
                        reachability = Reachability.Unknown,
                    )
                availability.observe(testBookId).test {
                    val state = awaitItem()
                    state.canPlay shouldBe true
                    state.canDownload shouldBe true
                    state.showServerWarning shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("no playback platform: canDownload=false") {
            runTest {
                val availability =
                    buildAvailability(
                        downloadStatus = BookDownloadStatus.NotDownloaded(bookId = "book-1"),
                        reachability = Reachability.Reachable,
                        playbackAvailable = false,
                    )
                availability.observe(testBookId).test {
                    val state = awaitItem()
                    state.isPlaybackAvailable shouldBe false
                    state.canDownload shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("queued download + wifi-only + metered network: isWaitingForWifi=true") {
            runTest {
                val queuedStatus =
                    BookDownloadStatus.InProgress(
                        bookId = "book-1",
                        totalFiles = 3,
                        downloadingFiles = 0,
                        completedFiles = 0,
                        totalBytes = 100_000L,
                        downloadedBytes = 0L,
                    )
                val availability =
                    buildAvailability(
                        downloadStatus = queuedStatus,
                        reachability = Reachability.Reachable,
                        unmetered = false,
                        wifiOnly = true,
                    )
                availability.observe(testBookId).test {
                    val state = awaitItem()
                    state.isWaitingForWifi shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
