package com.calypsan.listenup.client.domain.chapter

/**
 * Index of the chapter a position sits in: the last chapter whose start time is at or before [positionMs], or null
 * when there are no chapters (or the position is before the first). Pure. Book Detail's current chapter and Home's
 * Resume chapter both read it, so the two can never name different chapters.
 */
internal fun currentChapterIndex(
    chapterStartTimesMs: List<Long>,
    positionMs: Long,
): Int? = chapterStartTimesMs.indexOfLast { it <= positionMs }.takeIf { it >= 0 }
