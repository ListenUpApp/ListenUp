package com.calypsan.listenup.client.features.chaptereditor

import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterSetProblem
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_problem_blank_title
import listenup.composeapp.generated.resources.chapter_editor_problem_not_increasing
import listenup.composeapp.generated.resources.chapter_editor_problem_outside_book
import listenup.composeapp.generated.resources.chapter_editor_problem_title_too_long
import listenup.composeapp.generated.resources.chapter_editor_problem_unknown_chapter
import org.jetbrains.compose.resources.getString

/**
 * Says which chapter is wrong, in the number the user can actually see.
 *
 * A refused save is only useful if it points somewhere. Problems carry a chapter *id*, which is
 * meaningless on screen, so it is resolved to the row's position here — the same position
 * [numbered] puts in front of the title.
 *
 * Only the first problem is reported: one invalid boundary usually trips several rules at once
 * (a negative start is both outside the book and out of order), and listing all of them describes
 * the checker rather than the mistake.
 */
internal suspend fun chapterProblemMessage(
    problems: List<ChapterSetProblem>,
    chapters: List<Chapter>,
): String? {
    val problem = problems.firstOrNull() ?: return null
    val position = chapters.indexOfFirst { it.id == problem.chapterId }
    if (position < 0) return getString(Res.string.chapter_editor_problem_unknown_chapter)
    val number = position + 1

    return when (problem) {
        is ChapterSetProblem.BlankTitle -> {
            getString(Res.string.chapter_editor_problem_blank_title, number)
        }

        is ChapterSetProblem.TitleTooLong -> {
            getString(Res.string.chapter_editor_problem_title_too_long, number)
        }

        is ChapterSetProblem.NotStrictlyIncreasing -> {
            getString(Res.string.chapter_editor_problem_not_increasing, number)
        }

        is ChapterSetProblem.OutsideBook -> {
            getString(Res.string.chapter_editor_problem_outside_book, number)
        }
    }
}
