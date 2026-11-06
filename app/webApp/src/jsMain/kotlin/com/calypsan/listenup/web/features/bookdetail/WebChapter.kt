package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel

/**
 * One chapter as the workbench draws it.
 *
 * The shared [ChapterUiModel] is shaped for a list — a formatted duration string and nothing
 * positional. This view needs the opposite: a stable [number] to put in the URL (`?sel=9,10`),
 * and seconds to lay the chapter map out proportionally. Converting once, here, keeps that
 * arithmetic out of the composables.
 */
internal class WebChapter(
    val number: Int,
    val title: String,
    val startSec: Int,
    val durationSec: Int,
) {
    val endSec: Int get() = startSec + durationSec
}

/** Positions are 1-based and come from order, not from ids — that is what `?sel=` names. */
internal fun List<ChapterUiModel>.toWebChapters(): List<WebChapter> =
    mapIndexed { index, chapter ->
        WebChapter(
            number = index + 1,
            title = chapter.title,
            startSec = (chapter.startMs / MILLIS_PER_SECOND).toInt(),
            durationSec = (chapter.durationMs / MILLIS_PER_SECOND).toInt(),
        )
    }

private const val MILLIS_PER_SECOND = 1000
