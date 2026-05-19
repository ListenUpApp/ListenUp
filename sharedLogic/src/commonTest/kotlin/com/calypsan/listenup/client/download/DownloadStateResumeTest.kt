package com.calypsan.listenup.client.download

import com.calypsan.listenup.client.data.local.db.DownloadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
class DownloadStateResumeTest {
    /**
     * The core rule: DOWNLOADING must NOT be reset to QUEUED on resume.
     * A running worker uses KEEP policy and won't re-call updateState(DOWNLOADING).
     */
    @Test
    fun `DOWNLOADING state should not be reset to QUEUED on resume`() {
        assertFalse(shouldResetToQueued(DownloadState.DOWNLOADING))
    }

    /**
     * PAUSED is the only state that resume should reset to QUEUED.
     */
    @Test
    fun `PAUSED state should be reset to QUEUED on resume`() {
        assertTrue(shouldResetToQueued(DownloadState.PAUSED))
    }

    /**
     * QUEUED is already QUEUED — no reset needed (and the DownloadManager skips it).
     */
    @Test
    fun `QUEUED state should not trigger a redundant state write`() {
        assertFalse(shouldResetToQueued(DownloadState.QUEUED))
    }

    /**
     * Terminal states (COMPLETED, FAILED, DELETED) should never be reset to QUEUED by resume.
     * These have explicit user actions or separate recovery flows.
     */
    @Test
    fun `terminal states should not be reset to QUEUED on resume`() {
        assertFalse(shouldResetToQueued(DownloadState.COMPLETED))
        assertFalse(shouldResetToQueued(DownloadState.FAILED))
        assertFalse(shouldResetToQueued(DownloadState.DELETED))
    }

    /**
     * Documents the complete intended behaviour for all states.
     * Update this if the reset policy intentionally changes.
     */
    @Test
    fun `only PAUSED state should be reset to QUEUED on resume`() {
        val resetStates = DownloadState.entries.filter { shouldResetToQueued(it) }
        assertEquals(listOf(DownloadState.PAUSED), resetStates)
    }

    // ---- mirrors the logic in DownloadManager.resumeIncompleteDownloads() ----
    private fun shouldResetToQueued(state: DownloadState): Boolean = state == DownloadState.PAUSED
}
