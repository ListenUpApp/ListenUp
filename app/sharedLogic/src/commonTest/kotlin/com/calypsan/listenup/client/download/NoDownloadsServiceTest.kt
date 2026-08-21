package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Pins the two facts [NoDownloadsService] exists to state honestly. Both are load-bearing:
 * [PlaybackPreparer][com.calypsan.listenup.client.playback.PlaybackPreparer] reads
 * [DownloadService.supportsDownloads] to decide whether to fire a background download at all
 * (see `PlaybackPreparerBackgroundDownloadGateTest` for that gate's logic), and a mutation that
 * flipped this class back to `true` — restoring the original "every browser tap fires a doomed
 * download" bug — would leave every other test in this module green. Nothing else asserts on the
 * real implementation; the gate test only drives a mock.
 */
class NoDownloadsServiceTest :
    FunSpec({
        test("reports it cannot support downloads") {
            NoDownloadsService().supportsDownloads shouldBe false
        }

        test("downloadBook fails with the non-retryable NotSupported, not the retryable DownloadFailed") {
            runTest {
                val result = NoDownloadsService().downloadBook(BookId("book-1"))

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<DownloadError.NotSupported>()
                failure.error.isRetryable shouldBe false
            }
        }
    })
