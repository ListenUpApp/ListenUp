package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pure mapping: combines book identity + playback dynamics + surface metadata into
 * the appropriate sealed [NowPlayingState] variant.
 *
 * Variant selection priority:
 * 1. book == null && error == null      → [NowPlayingState.Idle]
 * 2. book == null && error != null      → [NowPlayingState.Error] with null bookId/title
 * 3. error != null                       → [NowPlayingState.Error] with book context
 * 4. otherwise (book != null)            → [NowPlayingState.Active]
 *
 * Pure function — same inputs always produce the same output. Unit-testable in
 * isolation. The VM's combine chain calls this in its terminal `map { ... }` block.
 */
fun mapToNowPlayingState(
    book: BookListItem?,
    dynamics: PlaybackDynamics,
    metadata: SurfaceMetadata,
): NowPlayingState {
    if (book == null) {
        if (metadata.error != null) {
            return NowPlayingState.Error(
                bookId = null,
                title = null,
                message = metadata.error.message,
                isRecoverable = metadata.error.isRecoverable,
            )
        }
        return NowPlayingState.Idle
    }

    if (metadata.error != null) {
        return NowPlayingState.Error(
            bookId = book.id.value,
            title = book.title,
            message = metadata.error.message,
            isRecoverable = metadata.error.isRecoverable,
        )
    }

    val chapter = metadata.currentChapter

    return NowPlayingState.Active(
        bookId = book.id.value,
        title = book.title,
        author = book.authorNames,
        coverPath = book.coverPath,
        coverHash = book.coverHash,
        authors = book.authors,
        narrators = book.narrators,
        seriesId = book.seriesId,
        seriesName = book.seriesName,
        chapterTitle = chapter?.title,
        chapterIndex = chapter?.index ?: 0,
        totalChapters = chapter?.totalChapters ?: 0,
        chapters =
            metadata.chapters.mapIndexed { index, c ->
                NowPlayingChapter(index = index, title = c.title, durationMs = c.duration)
            },
        isPlaying = dynamics.isPlaying,
        isBuffering = dynamics.isBuffering,
        playbackSpeed = dynamics.playbackSpeed,
        defaultPlaybackSpeed = metadata.defaultPlaybackSpeed,
    )
}

/**
 * Playback dynamics aggregated by the VM's `playbackDynamicsFlow` combine.
 */
data class PlaybackDynamics(
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val playbackSpeed: Float,
)

/**
 * Surface metadata aggregated by the VM's `surfaceMetadataFlow` combine.
 */
data class SurfaceMetadata(
    val currentChapter: PlaybackManager.ChapterInfo?,
    val chapters: List<Chapter> = emptyList(),
    val error: PlaybackManager.PlaybackErrorUiState?,
    val defaultPlaybackSpeed: Float,
)

/**
 * Fast-changing playback progress, split out of [NowPlayingState.Active] so the
 * per-tick position update (≈250ms) re-emits only this value, not the whole
 * 20-field player state. The player layout reads [NowPlayingState]; only the
 * seekbar + time labels read [PlaybackProgress].
 */
data class PlaybackProgress(
    val bookProgress: Float, // 0.0-1.0
    val bookPositionMs: Long,
    val bookDurationMs: Long,
    val chapterProgress: Float, // 0.0-1.0
    val chapterPositionMs: Long,
    val chapterDurationMs: Long,
) {
    val chapterPosition: Duration get() = chapterPositionMs.milliseconds
    val chapterDuration: Duration get() = chapterDurationMs.milliseconds

    companion object {
        val Zero = PlaybackProgress(0f, 0L, 0L, 0f, 0L, 0L)
    }
}

/**
 * Pure mapping of raw position + duration + current chapter into [PlaybackProgress].
 * Same arithmetic that previously lived inside [mapToNowPlayingState].
 */
fun mapToPlaybackProgress(
    currentPositionMs: Long,
    totalDurationMs: Long,
    chapter: PlaybackManager.ChapterInfo?,
): PlaybackProgress {
    val chapterDurationMs: Long =
        if (chapter != null) (chapter.endMs - chapter.startMs).coerceAtLeast(0L) else 0L
    val chapterPositionMs: Long =
        if (chapter != null) (currentPositionMs - chapter.startMs).coerceAtLeast(0L) else 0L
    val chapterProgress: Float =
        if (chapterDurationMs > 0L) (chapterPositionMs.toFloat() / chapterDurationMs).coerceIn(0f, 1f) else 0f
    val bookProgress: Float =
        if (totalDurationMs > 0L) (currentPositionMs.toFloat() / totalDurationMs).coerceIn(0f, 1f) else 0f
    return PlaybackProgress(
        bookProgress = bookProgress,
        bookPositionMs = currentPositionMs,
        bookDurationMs = totalDurationMs,
        chapterProgress = chapterProgress,
        chapterPositionMs = chapterPositionMs,
        chapterDurationMs = chapterDurationMs,
    )
}
