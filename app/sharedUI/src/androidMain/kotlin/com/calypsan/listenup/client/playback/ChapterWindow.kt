package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.Chapter

/**
 * A single-chapter view of book-relative playback position.
 *
 * System playback surfaces (Android Auto now-playing, the notification, the lock screen,
 * Bluetooth/AVRCP, Wear) present elapsed/remaining time and the seek bar for the *current
 * chapter*, not the whole book — the same way a music player shows the current track, not
 * the whole album. [ChapterWindow] is that re-presentation: a chapter-relative window over
 * the book's absolute timeline.
 *
 * @property chapterIndex Index into the chapters list, or `-1` when the book has no chapters
 *   (the whole book is presented as one window in that case).
 * @property windowStartMs Book-relative start of this window, in milliseconds.
 * @property windowDurationMs Length of this window, in milliseconds.
 * @property positionInWindowMs Position within the window, clamped to `[0, windowDurationMs]`.
 */
data class ChapterWindow(
    val chapterIndex: Int,
    val windowStartMs: Long,
    val windowDurationMs: Long,
    val positionInWindowMs: Long,
)

/**
 * Computes the [ChapterWindow] containing [bookPositionMs].
 *
 * Chapter resolution (last chapter whose start is at-or-before the position) mirrors
 * `PlaybackManagerImpl.updateCurrentChapter` so the in-app chapter indicator and the
 * system-surface window never disagree about which chapter is "current". A chapter's
 * window end is the next chapter's start, or the book's total duration for the last
 * chapter — chapters have no stored end, and this keeps windows contiguous even if a
 * chapter's own `duration` field doesn't exactly match the gap to the next chapter.
 *
 * When [chapters] is empty, the whole book is presented as a single window — the
 * chapterless-book fallback required by every caller of this function.
 */
fun currentChapterWindow(
    chapters: List<Chapter>,
    bookPositionMs: Long,
    totalBookDurationMs: Long,
): ChapterWindow {
    if (chapters.isEmpty()) {
        return ChapterWindow(
            chapterIndex = -1,
            windowStartMs = 0L,
            windowDurationMs = totalBookDurationMs,
            positionInWindowMs = bookPositionMs.coerceIn(0L, totalBookDurationMs),
        )
    }

    val index = chapters.indexOfLast { it.startTime <= bookPositionMs }.coerceAtLeast(0)
    val chapter = chapters[index]
    val windowEndMs = chapters.getOrNull(index + 1)?.startTime ?: totalBookDurationMs
    val windowDurationMs = (windowEndMs - chapter.startTime).coerceAtLeast(0L)

    return ChapterWindow(
        chapterIndex = index,
        windowStartMs = chapter.startTime,
        windowDurationMs = windowDurationMs,
        positionInWindowMs = (bookPositionMs - chapter.startTime).coerceIn(0L, windowDurationMs),
    )
}

/**
 * Translates a chapter-relative seek target (as reported by a system surface's seek bar)
 * into a book-relative position, clamped to this window's bounds.
 */
fun ChapterWindow.seekTargetToBookPosition(chapterRelativePositionMs: Long): Long =
    windowStartMs + chapterRelativePositionMs.coerceIn(0L, windowDurationMs)

/**
 * Book-relative target for a "previous" (skip-to-previous-chapter) command.
 *
 * Standard media-player "restart" behavior: more than [restartThresholdMs] into the
 * current chapter restarts it (returns its start); otherwise the target is the previous
 * chapter's start. Clamped at the first chapter — there is never a previous chapter to
 * move into, but restarting the current (first) chapter is always the fallback.
 */
fun previousChapterTarget(
    chapters: List<Chapter>,
    bookPositionMs: Long,
    totalBookDurationMs: Long,
    restartThresholdMs: Long = 3_000L,
): Long {
    val window = currentChapterWindow(chapters, bookPositionMs, totalBookDurationMs)
    if (chapters.isEmpty() || window.chapterIndex <= 0 || window.positionInWindowMs > restartThresholdMs) {
        return window.windowStartMs
    }
    return chapters[window.chapterIndex - 1].startTime
}

/**
 * Book-relative target for a "next" (skip-to-next-chapter) command.
 *
 * Clamped at the last chapter — there is no next chapter to move into, so the target
 * stays at the current chapter's own start.
 */
fun nextChapterTarget(
    chapters: List<Chapter>,
    bookPositionMs: Long,
    totalBookDurationMs: Long,
): Long {
    val window = currentChapterWindow(chapters, bookPositionMs, totalBookDurationMs)
    if (chapters.isEmpty()) {
        return window.windowStartMs
    }
    return chapters.getOrNull(window.chapterIndex + 1)?.startTime ?: window.windowStartMs
}

/** True when a chapter precedes [window]'s chapter — gates COMMAND_SEEK_TO_PREVIOUS availability. */
fun hasPreviousChapter(
    chapters: List<Chapter>,
    window: ChapterWindow,
): Boolean = chapters.isNotEmpty() && window.chapterIndex > 0

/** True when a chapter follows [window]'s chapter — gates COMMAND_SEEK_TO_NEXT availability. */
fun hasNextChapter(
    chapters: List<Chapter>,
    window: ChapterWindow,
): Boolean = chapters.isNotEmpty() && window.chapterIndex < chapters.lastIndex
