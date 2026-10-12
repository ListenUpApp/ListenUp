package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.Chapter

/**
 * The chapter that contains [positionMs], or null when the book has no chapters.
 *
 * The single derivation of "which chapter is playing": `PlaybackManagerImpl`, `NativePlayerEngine` and the
 * session's not-yet-prepared state all call it, so the Android notification, the lock screen, the
 * highlighted row and the chapter label can never disagree (spec §4.2, the §9.2 bug guard).
 *
 * The last chapter ends at [totalDurationMs]; with no known duration it ends at its own start.
 */
internal fun chapterInfoAt(
    positionMs: Long,
    chapters: List<Chapter>,
    totalDurationMs: Long?,
): PlaybackManager.ChapterInfo? {
    if (chapters.isEmpty()) return null
    val index = chapters.indexOfLast { it.startTime <= positionMs }.coerceAtLeast(0)
    val chapter = chapters[index]
    val endMs = chapters.getOrNull(index + 1)?.startTime ?: totalDurationMs ?: chapter.startTime
    return PlaybackManager.ChapterInfo(
        index = index,
        title = chapter.title,
        startMs = chapter.startTime,
        endMs = endMs,
        remainingMs = (endMs - positionMs).coerceAtLeast(0),
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
