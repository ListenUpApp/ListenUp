package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.Chapter

/**
 * The chapter that contains [positionMs], or null when the book has no chapters.
 *
 * A [ChapterInfo] view of [chapterWindowAt], the single derivation of "which chapter is playing": `PlaybackManagerImpl`,
 * `NativePlayerEngine` and the session's not-yet-prepared state all call it, so the Android notification, the lock
 * screen, the highlighted row and the chapter label can never disagree (spec §4.2, the §9.2 bug guard).
 */
internal fun chapterInfoAt(
    positionMs: Long,
    chapters: List<Chapter>,
    totalDurationMs: Long?,
): ChapterInfo? {
    val window = chapterWindowAt(positionMs, chapters, totalDurationMs)
    val chapter = chapters.getOrNull(window.index) ?: return null
    return ChapterInfo(
        index = window.index,
        title = chapter.title,
        startMs = window.startMs,
        endMs = window.endMs,
        remainingMs = (window.endMs - positionMs).coerceAtLeast(0),
        totalChapters = chapters.size,
        isGenericTitle = isGenericChapterTitle(chapter.title),
    )
}

/** True for titles that carry no name of their own: empty, "Chapter 14", "Track 7", "Part 2", "12". */
internal fun isGenericChapterTitle(title: String): Boolean {
    val normalized = title.trim().lowercase()
    return normalized.isEmpty() ||
        normalized.matches(Regex("""^(chapter|part|track|section)\s*\d+$""")) ||
        normalized.matches(Regex("""^\d+$"""))
}
