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

        test("unreachable + completed download: canPlay=true, canDownload=false, no warning") {
            runTest {
                val availability =
                    buildAvailability(
                        downloadStatus = BookDownloadStatus.Completed(bookId = "book-1", totalBytes = 1024L),
                        reachability = Reachability.Unreachable,
                    )
                availability.observe(testBookId).test {
                    val state = awaitItem()
                    state.canPlay shouldBe true
                    state.canDownload shouldBe false
                    state.showServerWarning shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("unreachable + not downloaded: honestly blocked — play/download disabled, warning shown") {
            runTest {
                val availability =
                    buildAvailability(
                        downloadStatus = BookDownloadStatus.NotDownloaded(bookId = "book-1"),
                        reachability = Reachability.Unreachable,
                    )
                availability.observe(testBookId).test {
                    val state = awaitItem()
                    state.canPlay shouldBe false
                    state.canDownload shouldBe false
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

        test("unknown reachability + not downloaded: optimistic — play/download enabled, no warning") {
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

        // ========== Partially-downloaded books stay playable offline ==========
        //
        // "Never stranded": audio already on this device must play, even with the server down.
        // Gating on Completed refused to play chapters the listener had already downloaded — the
        // exact case a self-hosted server going offline mid-download produces.

        test("unreachable + partially downloaded: canPlay=true — the finished files are on disk") {
            runTest {
                val availability =
                    buildAvailability(
                        downloadStatus =
                            BookDownloadStatus.InProgress(
                                bookId = "book-1",
                                totalFiles = 10,
                                downloadingFiles = 1,
                                completedFiles = 4,
                                totalBytes = 1024L,
                                downloadedBytes = 400L,
                            ),
                        reachability = Reachability.Unreachable,
                    )
                availability.observe(testBookId).test {
                    val state = awaitItem()
                    state.canPlay shouldBe true
                    state.canDownload shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("unreachable + download failed with some files complete: canPlay=true") {
            runTest {
                val availability =
                    buildAvailability(
                        downloadStatus =
                            BookDownloadStatus.Failed(
                                bookId = "book-1",
                                errorMessage = "boom",
                                partiallyDownloadedFiles = 3,
                            ),
                        reachability = Reachability.Unreachable,
                    )
                availability.observe(testBookId).test {
                    val state = awaitItem()
                    state.canPlay shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("unreachable + paused download with some files complete: canPlay=true") {
            runTest {
                val availability =
                    buildAvailability(
                        downloadStatus =
                            BookDownloadStatus.Paused(
                                bookId = "book-1",
                                pausedFiles = 6,
                                completedFiles = 4,
                                downloadedBytes = 400L,
                                totalBytes = 1024L,
                            ),
                        reachability = Reachability.Unreachable,
                    )
                availability.observe(testBookId).test {
                    val state = awaitItem()
                    state.canPlay shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("unreachable + queued with nothing finished: canPlay=false — there is no audio yet") {
            runTest {
                val availability =
                    buildAvailability(
                        downloadStatus =
                            BookDownloadStatus.InProgress(
                                bookId = "book-1",
                                totalFiles = 10,
                                downloadingFiles = 0,
                                completedFiles = 0,
                                totalBytes = 1024L,
                                downloadedBytes = 0L,
                            ),
                        reachability = Reachability.Unreachable,
                    )
                availability.observe(testBookId).test {
                    val state = awaitItem()
                    state.canPlay shouldBe false
                    state.showServerWarning shouldBe true
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
