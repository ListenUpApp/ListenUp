package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow
import com.calypsan.listenup.client.domain.chapter.withOpenParts
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.client.presentation.bookdetail.GroupedChapters
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.milliseconds

/**
 * A marked book's chapters: the collapsed window with the part it sits in named above it, or with
 * [expanded] the whole outline, parts opening and closing. Which rows exist is the outline's answer,
 * so this only draws: a chapter row's number indexes [chapters].
 */
@Composable
internal fun BookChapterOutline(
    bookId: String,
    grouped: GroupedChapters,
    chapters: List<ChapterUiModel>,
    expanded: Boolean,
    canPlay: Boolean,
    onPlayFromChapter: (Long) -> Unit,
) {
    var toggled by remember(bookId) { mutableStateOf(emptySet<String>()) }
    val rows = if (expanded) grouped.rows.withOpenParts(toggled) else grouped.previewRows

    if (!expanded) grouped.previewLabel?.let { label -> P(attrs = { classes("bd-part-label") }) { Text(label) } }

    Div(attrs = { classes("bd-ch-list") }) {
        // A book is an outer tier: everything after its header sits inside it.
        var inBook = false
        rows.forEach { row ->
            val tier = if (inBook) "bd-in-book" else null
            when (row) {
                is ChapterOutlineRow.BookHeader -> {
                    inBook = true
                    key("book-${row.chapterId}") { H3(attrs = { classes("bd-book") }) { Text(row.title) } }
                }

                is ChapterOutlineRow.PartHeader -> {
                    key("part-${row.chapterId}") {
                        PartRow(
                            part = row,
                            isOpen = row.isOpen(toggled).takeIf { expanded && row.chapterCount > 0 },
                            tier = tier,
                            canPlay = canPlay,
                            onToggle = {
                                toggled = if (row.chapterId in toggled) toggled - row.chapterId else toggled + row.chapterId
                            },
                            onPlay = { onPlayFromChapter(row.startMs) },
                        )
                    }
                }

                is ChapterOutlineRow.ChapterRow -> {
                    chapters.getOrNull(row.number - 1)?.let { chapter ->
                        key(chapter.id) {
                            Div(attrs = { tier?.let { classes(it) } }) {
                                ChapterButton(chapter, row.number, canPlay, onPlayFromChapter, withMeta = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One part. Its play is a button of its own; with [isOpen] set, the rest of the row is the button
 * that opens and closes it. [isOpen] is null when there is nothing to open: any part in the collapsed
 * window, and one that is only its announcement. Then the row claims no expanded state at all.
 */
@Composable
private fun PartRow(
    part: ChapterOutlineRow.PartHeader,
    isOpen: Boolean?,
    tier: String?,
    canPlay: Boolean,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
) {
    val summary = partSummary(part)
    Div(attrs = {
        classes("bd-part")
        tier?.let { classes(it) }
        if (part.isCurrentChapter) classes("is-current")
    }) {
        if (isOpen == null) {
            Div(attrs = { classes("bd-part-name") }) { PartText(part.title, summary) }
        } else {
            Button(attrs = {
                classes("bd-part-open")
                attr("type", "button")
                attr("data-part", part.title)
                attr("aria-expanded", isOpen.toString())
                attr("aria-label", "${part.title}, $summary")
                onClick { onToggle() }
            }) {
                Icon(if (isOpen) WebIcon.ChevronDown else WebIcon.ChevronRight, size = CHEVRON_SIZE)
                PartText(part.title, summary)
            }
        }
        Button(attrs = {
            classes("bd-part-play")
            attr("type", "button")
            attr("aria-label", "Play ${part.title}")
            // The folded chapter has no row to mark, so its header says it is the current one.
            if (part.isCurrentChapter) attr("aria-current", "true")
            // Only when it can't play: a `disabled` attribute of any value disables the button.
            if (!canPlay) attr("disabled", "")
            onClick { onPlay() }
        }) { Icon(WebIcon.Play, size = PLAY_MARK_SIZE) }
    }
}

@Composable
private fun PartText(
    title: String,
    summary: String,
) {
    Span(attrs = { classes("bd-part-t") }) { Text(title) }
    Span(attrs = { classes("bd-part-sub") }) { Text(summary) }
}

/** "13 chapters · 3h 42m", or just the length of a part that is only its announcement. */
private fun partSummary(part: ChapterOutlineRow.PartHeader): String {
    val length = DurationFormatter.hoursMinutes(part.durationMs.milliseconds)
    return when (part.chapterCount) {
        0 -> length
        1 -> "1 chapter · $length"
        else -> "${part.chapterCount} chapters · $length"
    }
}

private const val CHEVRON_SIZE = 18
