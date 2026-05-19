package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.BookContributor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * UI state for now-playing surfaces (mini-player + full-screen player).
 *
 * Sealed hierarchy makes illegal state combinations unrepresentable:
 * - [Idle] — no book loaded; mini-player hidden
 * - [Preparing] — book is being prepared (transcoding/loading); shown with progress
 * - [Active] — playback-ready; full UI state populated
 * - [Error] — something failed; shown with optional book context for "Failed to play X"
 *
 * Replaces the pre-Phase-E2.2.1 flat data class with `isVisible`, `errorMessage`,
 * `isPreparing`, etc. — the boolean / nullable fields are now expressed as variants.
 *
 * Per-variant UI ephemera (`isExpanded`, picker visibility) lives in [NowPlayingOverlay]
 * and a separate `isExpanded: StateFlow<Boolean>`, both tail-combined into
 * [NowPlayingScreenState] by the VM.
 */
sealed interface NowPlayingState {
    /** No active playback session. Default state. Mini-player hidden. */
    data object Idle : NowPlayingState

    /** Book loaded; pre-playback transcoding / loading in flight. */
    data class Preparing(
        val bookId: String,
        val title: String,
        val author: String,
        val coverPath: String?,
        val coverBlurHash: String?,
        val progress: Int, // 0-100
        val message: String?,
    ) : NowPlayingState

    /** Playback-ready. Full UI state populated. */
    data class Active(
        val bookId: String,
        val title: String,
        val author: String,
        val coverPath: String?,
        val coverBlurHash: String?,
        val authors: List<BookContributor>,
        val narrators: List<BookContributor>,
        val seriesId: String?,
        val seriesName: String?,
        val chapterTitle: String?,
        val chapterIndex: Int,
        val totalChapters: Int,
        val isPlaying: Boolean,
        val isBuffering: Boolean,
        val playbackSpeed: Float,
        val defaultPlaybackSpeed: Float,
        val bookProgress: Float, // 0.0-1.0
        val bookPositionMs: Long,
        val bookDurationMs: Long,
        val chapterProgress: Float, // 0.0-1.0
        val chapterPositionMs: Long,
        val chapterDurationMs: Long,
    ) : NowPlayingState {
        val chapterPosition: Duration get() = chapterPositionMs.milliseconds
        val chapterDuration: Duration get() = chapterDurationMs.milliseconds

        val chapterLabel: String get() =
            if (totalChapters > 0) "Chapter ${chapterIndex + 1} of $totalChapters" else ""

        val hasMultipleAuthors: Boolean get() = authors.size > 1
        val hasMultipleNarrators: Boolean get() = narrators.size > 1
    }

    /** Error state. Optional book context for "Failed to play [title]". */
    data class Error(
        val bookId: String?,
        val title: String?,
        val message: String,
        val isRecoverable: Boolean,
    ) : NowPlayingState
}

/**
 * Type of contributor picker to show.
 */
enum class ContributorPickerType {
    AUTHORS,
    NARRATORS,
}
