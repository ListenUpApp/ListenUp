package com.calypsan.listenup.client.download

import app.cash.turbine.test
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.repository.FakeDownloadRepository
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private fun row(retryCount: Int = 0) =
    DownloadEntity(
        audioFileId = "af1",
        bookId = "b1",
        filename = "af1.m4b",
        fileIndex = 0,
        state = DownloadState.DOWNLOADING,
        localPath = null,
        totalBytes = 1_000L,
        downloadedBytes = 400L,
        queuedAt = 0L,
        startedAt = null,
        completedAt = null,
        errorMessage = null,
        retryCount = retryCount,
    )

/**
 * What a failed download run means. An interruption (the connection dropping under Doze or a Wi-Fi
 * change) is a wait, not a failure: it pauses with a reason, charges nothing and tells nobody. Only
 * a genuine failure spends the row's retry budget, so a download the system stopped many times
 * still has every retry left when something really goes wrong.
 */
class DownloadFailureVerdictTest :
    FunSpec({
        suspend fun settle(
            repository: FakeDownloadRepository,
            errorBus: ErrorBus,
            error: AppError,
        ) = settleDownloadFailure(
            audioFileId = "af1",
            error = error,
            repository = repository,
            errorBus = errorBus,
        )

        listOf(
            TransportError.NetworkUnavailable(debugInfo = "Software caused connection abort"),
            TransportError.Timeout(debugInfo = "Socket timeout has expired"),
        ).forEach { interruption ->
            test("a dropped connection (${interruption.code}) pauses to resume later, charging no retry and raising no error") {
                runTest {
                    val repository = FakeDownloadRepository(initial = listOf(row()))
                    val errorBus = ErrorBus()

                    errorBus.errors.test {
                        settle(repository, errorBus, interruption) shouldBe DownloadFailureVerdict.RETRY_LATER
                        expectNoEvents()
                    }

                    val settled = repository.entities.single()
                    settled.state shouldBe DownloadState.PAUSED
                    settled.pauseReason shouldBe DownloadPauseReason.NETWORK_LOST
                    settled.retryCount shouldBe 0
                    settled.downloadedBytes shouldBe 400L
                }
            }
        }

        test("a full disk still fails, tells the person, and is not retried") {
            runTest {
                val repository = FakeDownloadRepository(initial = listOf(row()))
                val errorBus = ErrorBus()

                errorBus.errors.test {
                    settle(
                        repository,
                        errorBus,
                        TransportError.NetworkUnavailable(debugInfo = "write failed: ENOSPC (No space left on device)"),
                    ) shouldBe DownloadFailureVerdict.GIVE_UP
                    awaitItem().shouldBeInstanceOf<DownloadError.InsufficientStorage>()
                }

                repository.entities.single().state shouldBe DownloadState.FAILED
            }
        }

        test("an ended session pauses until sign-in without spending a retry") {
            runTest {
                val repository = FakeDownloadRepository(initial = listOf(row()))
                val errorBus = ErrorBus()

                settle(repository, errorBus, AuthError.SessionExpired()) shouldBe DownloadFailureVerdict.GIVE_UP

                val settled = repository.entities.single()
                settled.state shouldBe DownloadState.PAUSED
                settled.pauseReason shouldBe DownloadPauseReason.SIGN_IN_REQUIRED
                settled.retryCount shouldBe 0
            }
        }

        test("three system stops then a real failure still leave the retry budget") {
            runTest {
                val repository = FakeDownloadRepository(initial = listOf(row()))
                val errorBus = ErrorBus()
                repeat(3) {
                    repository.markPaused("af1", DownloadPauseReason.INTERRUPTED)
                    repository.markDownloading("af1", startedAt = 0L)
                }

                errorBus.errors.test {
                    settle(
                        repository,
                        errorBus,
                        TransportError.Server5xx(statusCode = 503),
                    ) shouldBe DownloadFailureVerdict.RETRY_LATER
                    awaitItem().shouldBeInstanceOf<DownloadError.DownloadFailed>()
                }

                val settled = repository.entities.single()
                settled.state shouldBe DownloadState.FAILED
                settled.retryCount shouldBe 1
            }
        }

        test("a real failure on the last budgeted attempt gives up") {
            runTest {
                val repository = FakeDownloadRepository(initial = listOf(row(retryCount = 2)))

                settle(repository, ErrorBus(), TransportError.Server5xx(statusCode = 503)) shouldBe
                    DownloadFailureVerdict.GIVE_UP

                repository.entities.single().retryCount shouldBe 3
            }
        }
    })
