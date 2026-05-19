package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.BookListItem

/**
 * Pure mapping: combines book identity + playback dynamics + surface metadata into
 * the appropriate sealed [NowPlayingState] variant.
 *
 * Variant selection priority:
 * 1. book == null && error == null      → [NowPlayingState.Idle]
 * 2. book == null && error != null      → [NowPlayingState.Error] with null bookId/title
 * 3. error != null                       → [NowPlayingState.Error] with book context
 * 4. prepareProgress != null             → [NowPlayingState.Preparing]
 * 5. otherwise (book != null)            → [NowPlayingState.Active]
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

    val prepare = metadata.prepareProgress
    if (prepare != null) {
        return NowPlayingState.Preparing(
            bookId = book.id.value,
            title = book.title,
            author = book.authorNames,
            coverPath = book.coverPath,
            coverBlurHash = book.coverBlurHash,
            progress = prepare.progress,
            message = prepare.message,
        )
    }

    val chapter = metadata.currentChapter
    val chapterDurationMs: Long =
        if (chapter != null) {
            (chapter.endMs - chapter.startMs).coerceAtLeast(0L)
        } else {
            0L
        }
    val chapterPositionMs: Long =
        if (chapter != null) {
            (dynamics.currentPositionMs - chapter.startMs).coerceAtLeast(0L)
        } else {
            0L
        }
    val chapterProgress: Float =
        if (chapterDurationMs > 0L) {
            (chapterPositionMs.toFloat() / chapterDurationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
    val bookProgress: Float =
        if (dynamics.totalDurationMs > 0L) {
            (dynamics.currentPositionMs.toFloat() / dynamics.totalDurationMs).coerceIn(0f, 1f)
        } else {
            0f
        }

    return NowPlayingState.Active(
        bookId = book.id.value,
        title = book.title,
        author = book.authorNames,
        coverPath = book.coverPath,
        coverBlurHash = book.coverBlurHash,
        authors = book.authors,
        narrators = book.narrators,
        seriesId = book.seriesId,
        seriesName = book.seriesName,
        chapterTitle = chapter?.title,
        chapterIndex = chapter?.index ?: 0,
        totalChapters = chapter?.totalChapters ?: 0,
        isPlaying = dynamics.isPlaying,
        isBuffering = dynamics.isBuffering,
        playbackSpeed = dynamics.playbackSpeed,
        defaultPlaybackSpeed = metadata.defaultPlaybackSpeed,
        bookProgress = bookProgress,
        bookPositionMs = dynamics.currentPositionMs,
        bookDurationMs = dynamics.totalDurationMs,
        chapterProgress = chapterProgress,
        chapterPositionMs = chapterPositionMs,
        chapterDurationMs = chapterDurationMs,
    )
}

/**
 * Playback dynamics aggregated by the VM's `playbackDynamicsFlow` combine.
 */
data class PlaybackDynamics(
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val currentPositionMs: Long,
    val totalDurationMs: Long,
    val playbackSpeed: Float,
)

/**
 * Surface metadata aggregated by the VM's `surfaceMetadataFlow` combine.
 */
data class SurfaceMetadata(
    val currentChapter: PlaybackManager.ChapterInfo?,
    val prepareProgress: PlaybackManager.PrepareProgress?,
    val error: PlaybackManager.PlaybackErrorUiState?,
    val defaultPlaybackSpeed: Float,
)
