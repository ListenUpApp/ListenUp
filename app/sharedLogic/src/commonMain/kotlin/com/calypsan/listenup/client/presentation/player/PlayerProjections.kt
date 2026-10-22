package com.calypsan.listenup.client.presentation.player

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlayerPhase

/** The one main button for [phase] (§4.6). Pure. */
internal fun primaryControlFor(
    phase: PlayerPhase,
    isAdmin: Boolean,
): PrimaryControl =
    when (phase) {
        PlayerPhase.Playing, PlayerPhase.SleepFading -> PrimaryControl.Pause
        PlayerPhase.Paused, PlayerPhase.Restored -> PrimaryControl.Play
        PlayerPhase.Preparing, PlayerPhase.Buffering, PlayerPhase.Reconnecting -> PrimaryControl.Busy
        PlayerPhase.Finished -> PrimaryControl.ListenAgain
        is PlayerPhase.Failed -> controlFor(phase.error, isAdmin)
        is PlayerPhase.Unavailable -> controlFor(phase.reason, isAdmin)
    }

private fun controlFor(
    error: AppError,
    isAdmin: Boolean,
): PrimaryControl =
    when (error) {
        is AuthError.SessionExpired -> PrimaryControl.SignIn
        is BookError.HeldForReview -> if (isAdmin) PrimaryControl.OpenInbox else PrimaryControl.Close
        is PlaybackError.NoLongerAvailable -> PrimaryControl.Close
        is PlaybackError.AutoplayBlocked -> PrimaryControl.TapToPlay
        is PlaybackError.BlockedInBackground -> PrimaryControl.Play
        else -> PrimaryControl.Retry
    }

/** §3.5: back N shows only where there is a settled position to go back from. neither stall shows it (S12). */
internal fun showsSkipBackFor(phase: PlayerPhase): Boolean =
    phase == PlayerPhase.Playing ||
        phase == PlayerPhase.Paused ||
        phase == PlayerPhase.Restored ||
        phase == PlayerPhase.SleepFading

/**
 * The status slot (§4.7). [timelineFullyDownloaded] is null before a prepare; then [bookDownloaded] (from the
 * download status) decides local vs streaming. Pure.
 */
@Suppress("LongParameterList") // every input is one of §4.7's four sources, named
internal fun audioSourceOf(
    phase: PlayerPhase,
    outputDeviceName: String?,
    timelineFullyDownloaded: Boolean?,
    anyFileTranscoded: Boolean,
    bookDownloaded: Boolean,
    isDeviceOnline: Boolean,
    isServerReachable: Boolean,
    offlineCopiesSupported: Boolean,
): AudioSource {
    val notPlayable =
        phase is PlayerPhase.Unavailable &&
            (phase.reason is BookError.HeldForReview || phase.reason is PlaybackError.NoLongerAvailable)
    val local = timelineFullyDownloaded ?: bookDownloaded
    val unreachable = !isDeviceOnline || !isServerReachable
    return when {
        notPlayable -> AudioSource.NotPlayable
        outputDeviceName != null -> AudioSource.Casting(outputDeviceName)
        local && !isDeviceOnline -> AudioSource.DownloadedOffline
        local -> AudioSource.Downloaded
        unreachable && !offlineCopiesSupported -> AudioSource.ServerUnreachable
        unreachable -> AudioSource.Offline
        anyFileTranscoded -> AudioSource.StreamingTranscoded
        else -> AudioSource.Streaming
    }
}

/** The current chapter as surfaces draw it; null [info] is a chapterless book's one whole-book chapter (§3.2). */
internal fun playerChapterOf(
    info: PlaybackManager.ChapterInfo?,
    bookDurationMs: Long,
): PlayerChapter =
    info?.let { chapter ->
        PlayerChapter(
            index = chapter.index,
            number = chapter.index + 1,
            total = chapter.totalChapters,
            title = chapter.title,
            isGenericTitle = chapter.isGenericTitle,
            isWholeBook = false,
            startMs = chapter.startMs,
            durationMs = (chapter.endMs - chapter.startMs).coerceAtLeast(0L),
        )
    } ?: PlayerChapter(
        index = 0,
        number = 1,
        total = 1,
        title = "",
        isGenericTitle = true,
        isWholeBook = true,
        startMs = 0L,
        durationMs = bookDurationMs,
    )

/** The chapter rows, with exactly [currentIndex] current. */
internal fun chapterRowsOf(
    chapters: List<Chapter>,
    currentIndex: Int?,
): List<PlayerChapterRow> =
    chapters.mapIndexed { index, chapter ->
        PlayerChapterRow(
            index = index,
            number = index + 1,
            title = chapter.title,
            startMs = chapter.startTime,
            durationMs = chapter.duration,
            isCurrent = index == currentIndex,
        )
    }

/** A failure the reader must act on; a refusal or a lapsed session is not one (the `States-*` boards). */
internal fun AppError.isFailureToFix(): Boolean =
    this !is PlaybackError.AutoplayBlocked &&
        this !is PlaybackError.BlockedInBackground &&
        this !is AuthError.SessionExpired
