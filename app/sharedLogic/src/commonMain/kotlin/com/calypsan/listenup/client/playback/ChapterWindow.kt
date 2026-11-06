package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.Chapter

/**
 * The chapter a book-relative position sits in, as a window over the book's timeline.
 *
 * The single derivation of "which chapter is playing, and where in it": the in-app chapter label and highlighted
 * row, Book Detail's current chapter, and Android's notification, lock screen and Auto card all read it, so they can
 * never disagree. Build one with [chapterWindowAt].
 *
 * @property index Index into the chapters list, or `-1` when the book has no chapters (the whole book is then one
 *   window).
 * @property startMs Book-relative start of the window.
 * @property endMs Book-relative end of the window: the next chapter's start, or the book's total for the last
 *   chapter.
 * @property positionMs The book-relative position the window was resolved for, unclamped.
 * @property isBeforeFirstChapter True when [positionMs] precedes the first chapter's start (an intro before
 *   "Chapter 1"). The window is then the first chapter's, so a surface that needs a window has one; a surface that
 *   treats "not yet in a chapter" like "not started" reads this flag.
 */
data class ChapterWindow(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val positionMs: Long,
    val isBeforeFirstChapter: Boolean,
) {
    /** Length of the window. */
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /** [positionMs] within the window, clamped to `[0, durationMs]`. */
    val positionInWindowMs: Long get() = (positionMs - startMs).coerceIn(0L, durationMs)

    /** The time left in the window: content time, never divided by the speed. */
    val remainingMs: Long get() = durationMs - positionInWindowMs

    /** The book-relative position of [offsetMs] into this window, clamped to the window's bounds. */
    fun bookPositionAt(offsetMs: Long): Long = startMs + offsetMs.coerceIn(0L, durationMs)

    /** The book-relative position [fraction] of the way through this window; [fraction] is clamped to `0..1`. */
    fun bookPositionAtFraction(fraction: Float): Long = startMs + (durationMs * fraction.coerceIn(0f, 1f)).toLong()
}

/**
 * The [ChapterWindow] containing [positionMs].
 *
 * The chapter is the last one whose start is at or before the position, so a boundary belongs to the chapter that
 * starts there. A chapter has no stored end: its window ends where the next one starts, which keeps windows
 * contiguous even when a chapter's own `duration` disagrees with the gap. The last chapter ends at
 * [totalDurationMs]; with no known duration it ends at its own start. With no [chapters] the whole book is one
 * window.
 */
fun chapterWindowAt(
    positionMs: Long,
    chapters: List<Chapter>,
    totalDurationMs: Long?,
): ChapterWindow {
    if (chapters.isEmpty()) {
        return ChapterWindow(
            index = -1,
            startMs = 0L,
            endMs = totalDurationMs ?: 0L,
            positionMs = positionMs,
            isBeforeFirstChapter = false,
        )
    }
    val containing = chapters.indexOfLast { it.startTime <= positionMs }
    val index = containing.coerceAtLeast(0)
    val chapter = chapters[index]
    return ChapterWindow(
        index = index,
        startMs = chapter.startTime,
        endMs = chapters.getOrNull(index + 1)?.startTime ?: totalDurationMs ?: chapter.startTime,
        positionMs = positionMs,
        isBeforeFirstChapter = containing < 0,
    )
}
