package com.calypsan.listenup.client.presentation.download

import app.cash.turbine.test
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.repository.FakeDownloadRepository
import com.calypsan.listenup.client.domain.model.DownloadPhase
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.test.fake.FakeNetworkMonitor
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** The Library's cover badges: one phase per book with a download, from the same rule as Book Detail. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadPhasesViewModelTest :
    FunSpec({
        beforeTest { Dispatchers.setMain(UnconfinedTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        fun row(
            bookId: String,
            state: DownloadState,
        ) = DownloadEntity(
            audioFileId = "$bookId-f0",
            bookId = bookId,
            filename = "f0.mp3",
            fileIndex = 0,
            state = state,
            localPath = null,
            totalBytes = 100,
            downloadedBytes = 0,
            queuedAt = 0,
            startedAt = null,
            completedAt = null,
            errorMessage = null,
        )

        test("each book with a download has its phase; a queued book reads Waiting for Wi-Fi once Wi-Fi-only holds it") {
            runTest {
                val unmetered = FakeNetworkMonitor(unmetered = true)
                val vm =
                    DownloadPhasesViewModel(
                        downloadRepository =
                            FakeDownloadRepository(
                                initial =
                                    listOf(
                                        row("moving", DownloadState.DOWNLOADING),
                                        row("queued", DownloadState.QUEUED),
                                        row("done", DownloadState.COMPLETED),
                                        row("gone", DownloadState.CANCELLED),
                                    ),
                            ),
                        localPreferences = mock<LocalPreferences> { every { wifiOnlyDownloads } returns MutableStateFlow(true) },
                        networkMonitor = unmetered,
                    )

                vm.phases.test {
                    val onWifi = expectMostRecentItem()
                    onWifi["moving"] shouldBe DownloadPhase.Downloading(downloadedBytes = 0, totalBytes = 100)
                    onWifi["queued"] shouldBe DownloadPhase.Queued
                    onWifi["done"] shouldBe DownloadPhase.Downloaded
                    onWifi.containsKey("gone") shouldBe false

                    unmetered.onUnmeteredNetworkFlow.value = false
                    awaitItem()["queued"] shouldBe DownloadPhase.WaitingForWifi
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
