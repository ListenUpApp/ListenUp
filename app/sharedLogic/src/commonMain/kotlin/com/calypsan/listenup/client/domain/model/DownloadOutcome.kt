package com.calypsan.listenup.client.domain.model

/**
 * Successful outcomes of a download orchestration request.
 *
 * Wrapped in [com.calypsan.listenup.api.result.AppResult.Success] when the request succeeds.
 * Failures use [com.calypsan.listenup.api.result.AppResult.Failure] with a
 * [com.calypsan.listenup.api.error.DownloadError].
 *
 * Replaces the legacy `DownloadResult` sealed type — fallible downloads now return
 * `AppResult<DownloadOutcome>`.
 */
sealed interface DownloadOutcome {
    /** New download enqueued (one or more files queued for fetch). */
    data object Started : DownloadOutcome

    /** All files for this book are already in [com.calypsan.listenup.client.data.local.db.DownloadState.COMPLETED]; no work enqueued. */
    data object AlreadyDownloaded : DownloadOutcome

    /**
     * Pre-flight storage check failed; no work enqueued.
     *
     * Carried as a success outcome (not a failure) because it's a deterministic, user-actionable
     * branch — the caller decides whether to surface a "free up space" prompt or proceed without
     * downloading. Modeling it as a failure would force every caller to special-case it out of
     * the failure handler.
     */
    data class InsufficientStorage(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : DownloadOutcome
}
