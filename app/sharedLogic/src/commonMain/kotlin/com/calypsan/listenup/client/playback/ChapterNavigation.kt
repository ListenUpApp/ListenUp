package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.Chapter

/**
 * Where "previous chapter" and "next chapter" land (spec §3.3, S8): the one rule for the app, the notification and
 * lock screen, headsets, Android Auto, CarPlay, AirPods, keyboard media keys and the browser's Media Session.
 * Positions are book-relative milliseconds. Pure. An object, so Swift Export exposes it as `ChapterNavigation.shared`.
 */
object ChapterNavigation {
    /** How far into a chapter "previous" restarts it rather than going back one. */
    const val RESTART_AFTER_MS: Long = 3_000L

    /**
     * The start of the current chapter once it has run for more than [RESTART_AFTER_MS], otherwise the start of the
     * previous one. Chapter 1 always restarts. Null in a book with no chapters: there previous does nothing, rather
     * than jump to 0:00. Defined by elapsed time, so there is no hidden "pressed again within 3 s" timer.
     */
    fun previousStartMs(
        positionMs: Long,
        chapters: List<Chapter>,
    ): Long? {
        if (chapters.isEmpty()) return null
        val current = currentIndex(positionMs, chapters)
        val chapterStart = chapters[current].startTime
        val restart = positionMs - chapterStart > RESTART_AFTER_MS || current == 0
        return if (restart) chapterStart else chapters[current - 1].startTime
    }

    /**
     * Whether previous does anything: what every surface's previous control is enabled by. Always, in a chaptered
     * book (at worst it restarts the chapter); never in a chapterless one.
     */
    fun hasPrevious(chapters: List<Chapter>): Boolean = chapters.isNotEmpty()

    /** The next chapter's start, or null at the last chapter and in a chapterless book: next does nothing there. */
    fun nextStartMs(
        positionMs: Long,
        chapters: List<Chapter>,
    ): Long? {
        if (chapters.isEmpty()) return null
        return chapters.getOrNull(currentIndex(positionMs, chapters) + 1)?.startTime
    }

    /** Whether next does anything from [positionMs]: what every surface's next control is enabled by. */
    fun hasNext(
        positionMs: Long,
        chapters: List<Chapter>,
    ): Boolean = nextStartMs(positionMs, chapters) != null

    private fun currentIndex(
        positionMs: Long,
        chapters: List<Chapter>,
    ): Int = chapters.indexOfLast { it.startTime <= positionMs }.coerceAtLeast(0)
}
