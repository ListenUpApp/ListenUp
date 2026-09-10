package com.calypsan.listenup.web.features.chaptereditor

import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterSetProblem

/**
 * Says which chapter is wrong, in the number the user can actually see.
 *
 * A refused save is only useful if it points somewhere. Problems carry a chapter *id*, which is
 * meaningless on screen, so it is resolved to the row's position here — the same position the list
 * puts in front of the title.
 *
 * ⛔ Only the first problem is reported. One invalid boundary usually trips several rules at once —
 * a negative start is both outside the book and out of order — and listing all of them describes
 * the checker rather than the mistake.
 */
internal fun chapterProblemText(
    problems: List<ChapterSetProblem>,
    chapters: List<Chapter>,
): String? {
    val problem = problems.firstOrNull() ?: return null
    val position = chapters.indexOfFirst { it.id == problem.chapterId }
    if (position < 0) return "A chapter that is no longer in the list is invalid."
    val number = position + 1

    return when (problem) {
        is ChapterSetProblem.BlankTitle -> "Chapter $number needs a title."
        is ChapterSetProblem.TitleTooLong -> "Chapter $number’s title is too long."
        is ChapterSetProblem.NotStrictlyIncreasing -> "Chapter $number starts at or before the one before it."
        is ChapterSetProblem.OutsideBook -> "Chapter $number starts outside the book."
    }
}
