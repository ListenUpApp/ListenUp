package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.Chapter

/** How far into a chapter "previous" restarts it rather than going back one (spec §3.3, S8). */
internal const val RESTART_CHAPTER_AFTER_MS: Long = 3_000L

/**
 * Where "previous chapter" lands from [positionMs]: the start of the current chapter once it has run for
 * more than [RESTART_CHAPTER_AFTER_MS], otherwise the start of the previous one. Chapter 1, and a book with
 * no chapters, always restart. Defined by elapsed time, so no hidden "pressed again within 3 s" timer.
 */
internal fun previousChapterTargetMs(
    positionMs: Long,
    chapters: List<Chapter>,
): Long {
    if (chapters.isEmpty()) return 0L
    val current = chapters.indexOfLast { it.startTime <= positionMs }.coerceAtLeast(0)
    val chapterStart = chapters[current].startTime
    val restart = positionMs - chapterStart > RESTART_CHAPTER_AFTER_MS || current == 0
    return if (restart) chapterStart else chapters[current - 1].startTime
}

/** Where "next chapter" lands from [positionMs], or null at the last chapter (the control is disabled there). */
internal fun nextChapterTargetMs(
    positionMs: Long,
    chapters: List<Chapter>,
): Long? {
    if (chapters.isEmpty()) return null
    val current = chapters.indexOfLast { it.startTime <= positionMs }.coerceAtLeast(0)
    return chapters.getOrNull(current + 1)?.startTime
}
