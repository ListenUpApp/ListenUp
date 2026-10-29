package com.calypsan.listenup.client.domain.chapter

/**
 * Index of the chapter a position sits in: the last chapter whose start time is at or before [positionMs], or null
 * when there are no chapters (or the position is before the first). Pure. Book Detail's current chapter reads it.
 */
internal fun currentChapterIndex(
    chapterStartTimesMs: List<Long>,
    positionMs: Long,
): Int? = chapterStartTimesMs.indexOfLast { it <= positionMs }.takeIf { it >= 0 }
