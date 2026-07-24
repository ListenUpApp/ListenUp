package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import kotlinx.coroutines.flow.StateFlow

/**
 * Orchestrates playback startup and coordinates between UI, service, and data layers.
 *
 * Defined as an `interface` so that test fakes (e.g., a fake VM dependency in
 * `NowPlayingViewModelTest`) can implement it without inheritance gymnastics, and
 * so that platform-specific seams that only need a narrow surface can depend on
 * the interface rather than the concrete [PlaybackManagerImpl].
 *
 * Responsibilities (implemented by [PlaybackManagerImpl]):
 * - Parse audio files from BookEntity
 * - Build [PlaybackTimeline] for position translation
 * - Prepare authentication for streaming
 * - Track current playback state (position, playing status)
 * - Provide a central control interface for playback
 * - Trigger background downloads for offline availability
 * - Negotiate audio format with server for transcoding support
 *
 * Inherits [PlaybackStateProvider] (read seam used by logout/session cleanup) and
 * [PlaybackStateWriter] (write seam used by Android's MediaControllerHolder
 * Player.Listener).
 */
interface PlaybackManager :
    PlaybackStateProvider,
    PlaybackStateWriter {
    // ====================================================================
    // Read surface — flows consumed by VMs, services, and UI.
    // ====================================================================

    /** Active timeline for the current book, or null when no book is active. */
    val currentTimeline: StateFlow<PlaybackTimeline?>

    /** True when the player is actively playing audio (not paused/buffering). */
    val isPlaying: StateFlow<Boolean>

    /** True when the player is buffering — distinct from [isPlaying]. */
    val isBuffering: StateFlow<Boolean>

    /** Current playback position within the book in milliseconds. */
    val currentPositionMs: StateFlow<Long>

    /** Total duration of the current book in milliseconds. */
    val totalDurationMs: StateFlow<Long>

    /** Current playback speed (1.0 = normal). */
    val playbackSpeed: StateFlow<Float>

    /** Aggregate playback state (Idle/Buffering/Playing/Paused/Ended/Error). */
    val playbackState: StateFlow<PlaybackState>

    /** Current playback error for display to the user (null when no error). */
    val playbackError: StateFlow<PlaybackErrorUiState?>

    /** Chapter list for the current book. */
    val chapters: StateFlow<List<Chapter>>

    /** Currently active chapter (derived from [currentPositionMs] + [chapters]). */
    val currentChapter: StateFlow<ChapterInfo?>

    /**
     * Callback invoked whenever the active chapter changes — used by the Android
     * playback service to update the media notification. Set to `null` when the
     * service detaches.
     */
    var onChapterChanged: ((ChapterInfo) -> Unit)?

    // ====================================================================
    // Write surface — operations the consolidated VM, platform actuals, and
    // the Android playback service invoke.
    // ====================================================================

    /** Set the current book ID — call only when playback is confirmed to proceed. */
    fun activateBook(bookId: BookId)

    /**
     * Prepare for playback of a book.
     *
     * @return [PrepareResult] with timeline + resume position, or null on failure.
     */
    suspend fun prepareForPlayback(bookId: BookId): PrepareResult?

    /**
     * Start playback using a platform [AudioPlayer]. Bridges the prepared timeline
     * to the player and connects state flows back into [PlaybackManager] for
     * position tracking.
     */
    suspend fun startPlayback(
        player: AudioPlayer,
        resumePositionMs: Long = 0L,
        resumeSpeed: Float = 1.0f,
    )

    /**
     * Called when the user explicitly changes playback speed for the current
     * book. Sets per-book speed via [ProgressTracker]; does NOT mutate the
     * global default.
     */
    fun onSpeedChanged(speed: Float)

    /**
     * Reset the current book's speed to the universal default (passed in as
     * [defaultSpeed]).
     */
    fun onSpeedReset(defaultSpeed: Float)

    // ====================================================================
    // Nested types
    //
    // Kept nested (not promoted to top-level) because consumer code references
    // them as `PlaybackManager.PrepareResult`, `PlaybackManager.PlaybackErrorUiState`,
    // `PlaybackManager.ChapterInfo` across shared, composeApp/{android,desktop}Main,
    // and tests. Promoting to top-level would cause an avoidable churn through every
    // reference site.
    // ====================================================================

    /** Result of preparing for playback. */
    data class PrepareResult(
        val timeline: PlaybackTimeline,
        val bookTitle: String,
        val bookAuthor: String,
        val seriesName: String?,
        val coverPath: String?,
        val totalChapters: Int,
        val resumePositionMs: Long,
        val resumeSpeed: Float,
    )

    /** Playback error for display to the user. */
    data class PlaybackErrorUiState(
        val message: String,
        val isRecoverable: Boolean,
        val timestampMs: Long,
    )

    /** Current chapter information for notification and UI. */
    data class ChapterInfo(
        val index: Int,
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val remainingMs: Long,
        val totalChapters: Int,
        val isGenericTitle: Boolean,
    )
}
