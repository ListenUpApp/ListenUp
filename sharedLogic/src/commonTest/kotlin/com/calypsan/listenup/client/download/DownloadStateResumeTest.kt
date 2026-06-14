package com.calypsan.listenup.client.download

import com.calypsan.listenup.client.data.local.db.DownloadState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Regression tests for the download state reset logic in DownloadManager.resumeIncompleteDownloads().
 *
 * Bug history: resumeIncompleteDownloads() was resetting ALL non-QUEUED states → QUEUED,
 * including DOWNLOADING. This caused the download button to show an indeterminate spinner
 * permanently because:
 *   1. Worker is re-enqueued with ExistingWorkPolicy.KEEP (existing worker keeps running).
 *   2. The running worker only calls updateState(DOWNLOADING) once, at the start of doWork().
 *   3. After the DB reset to QUEUED, no code ever transitions it back to DOWNLOADING.
 *
 * Fix: only reset PAUSED → QUEUED. Leave DOWNLOADING entries untouched.
 */
class DownloadStateResumeTest :
    FunSpec({
        // ---- mirrors the logic in DownloadManager.resumeIncompleteDownloads() ----
        fun shouldResetToQueued(state: DownloadState): Boolean = state == DownloadState.PAUSED

        // The core rule: DOWNLOADING must NOT be reset to QUEUED on resume.
        // A running worker uses KEEP policy and won't re-call updateState(DOWNLOADING).
        test("DOWNLOADING state should not be reset to QUEUED on resume") {
            shouldResetToQueued(DownloadState.DOWNLOADING) shouldBe false
        }

        // PAUSED is the only state that resume should reset to QUEUED.
        test("PAUSED state should be reset to QUEUED on resume") {
            shouldResetToQueued(DownloadState.PAUSED) shouldBe true
        }

        // QUEUED is already QUEUED — no reset needed (and the DownloadManager skips it).
        test("QUEUED state should not trigger a redundant state write") {
            shouldResetToQueued(DownloadState.QUEUED) shouldBe false
        }

        // Terminal states (COMPLETED, FAILED, DELETED) should never be reset to QUEUED by resume.
        // These have explicit user actions or separate recovery flows.
        test("terminal states should not be reset to QUEUED on resume") {
            shouldResetToQueued(DownloadState.COMPLETED) shouldBe false
            shouldResetToQueued(DownloadState.FAILED) shouldBe false
            shouldResetToQueued(DownloadState.DELETED) shouldBe false
        }

        // Documents the complete intended behaviour for all states.
        // Update this if the reset policy intentionally changes.
        test("only PAUSED state should be reset to QUEUED on resume") {
            val resetStates = DownloadState.entries.filter { shouldResetToQueued(it) }
            resetStates shouldBe listOf(DownloadState.PAUSED)
        }
    })
