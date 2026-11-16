package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.util.bleedHorizontally
import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow
import com.calypsan.listenup.client.features.nowplaying.spokenDuration
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReadership
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.client.presentation.bookdetail.CurrentChapter
import com.calypsan.listenup.client.presentation.bookdetail.GroupedChapters
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_chapter_count
import listenup.composeapp.generated.resources.book_detail_chapter_count_one
import listenup.composeapp.generated.resources.book_detail_part_summary
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/** The divider's rule: heavy enough to read as a break while the list flings past. */
private val PartRuleThickness = 2.dp

/** The space above a part divider: with the list's own 4 dp gap, the board's 28 dp. */
private val PartSpaceAbove = 24.dp

/** Inside an omnibus a part is one tier down, so it takes less room than the book above it. */
private val OmnibusPartSpaceAbove = 16.dp

/** The space above a Book heading: what separates one book of an omnibus from the next. */
private val BookSpaceAbove = 36.dp

/** What a chapter row needs to play. */
internal data class ChapterPlayback(
    val current: CurrentChapter?,
    val canPlay: Boolean,
    val onPlayFromChapter: (Long) -> Unit,
)

/**
 * A marked book's chapters, every one of them a row: the collapsed window, or with [expanded] the
 * whole outline. A part is a divider above its first chapter and a Book marker a heading above its
 * parts; neither is a button, holds a reader, or is ever current. Which rows exist is the outline's
 * answer, so this only draws: a chapter row's index reaches its entry in [chapters].
 *
 * Everyone else reading sits on their chapter's row ([readership]).
 */
@Composable
internal fun BookChapterOutline(
    grouped: GroupedChapters,
    chapters: List<ChapterUiModel>,
    expanded: Boolean,
    playback: ChapterPlayback,
    readership: ChapterReadership = ChapterReadership.None,
) {
    val rows = if (expanded) grouped.rows else grouped.previewRows
    val isOmnibus = remember(grouped) { grouped.rows.any { it is ChapterOutlineRow.BookHeader } }

    rows.forEachIndexed { position, row ->
        // The card's header already sets the first line apart: it needs no space of its own.
        val isFirst = position == 0
        when (row) {
            is ChapterOutlineRow.BookHeader -> {
                key("book-${row.chapterId}") { BookHeading(row.title, isFirst) }
            }

            is ChapterOutlineRow.PartHeader -> {
                key("part-${row.chapterId}") { PartDivider(row, isFirst, isOmnibus) }
            }

            is ChapterOutlineRow.ChapterRow -> {
                chapters.getOrNull(row.index)?.let { chapter ->
                    key(chapter.id) {
                        ChapterRow(
                            chapter = chapter,
                            number = row.number,
                            current = playback.current.takeIf { chapter.isCurrent },
                            canPlay = playback.canPlay,
                            onPlayFromChapter = playback.onPlayFromChapter,
                            readers = readership.onChapter(chapter.id),
                        )
                    }
                }
            }
        }
    }
}

/** A Book marker: a heading one tier above its parts, set apart by space rather than a rule. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BookHeading(
    title: String,
    isFirst: Boolean,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmallEmphasized,
        modifier =
            Modifier
                .padding(top = if (isFirst) 0.dp else BookSpaceAbove)
                .padding(horizontal = 12.dp)
                .padding(bottom = 4.dp)
                .semantics { heading() },
    )
}

/**
 * Where a part begins: space, a rule across the whole card, then the title with "13 chapters · 5h 16m"
 * under it. A heading, not a button, read as one sentence: "Part IV: Reaper, 13 chapters, 5 hours
 * 16 minutes".
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PartDivider(
    part: ChapterOutlineRow.PartHeader,
    isFirst: Boolean,
    isOmnibus: Boolean,
) {
    val count = chapterCount(part.chapterCount)
    val length = part.durationMs.milliseconds
    val summary = stringResource(Res.string.book_detail_part_summary, count, DurationFormatter.hoursMinutes(length))
    val sentence = listOf(part.title, count, spokenDuration(length)).joinToString(", ")
    val spaceAbove: Dp =
        when {
            isFirst -> 0.dp
            isOmnibus -> OmnibusPartSpaceAbove
            else -> PartSpaceAbove
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = spaceAbove)
                .clearAndSetSemantics {
                    heading()
                    contentDescription = sentence
                },
    ) {
        HorizontalDivider(
            modifier = Modifier.bleedHorizontally(ChaptersCardInset),
            thickness = PartRuleThickness,
            color = MaterialTheme.colorScheme.outline,
        )
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = part.title,
                style =
                    if (isOmnibus) {
                        MaterialTheme.typography.titleMediumEmphasized
                    } else {
                        MaterialTheme.typography.titleLargeEmphasized
                    },
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun chapterCount(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.book_detail_chapter_count_one)
    } else {
        stringResource(Res.string.book_detail_chapter_count, count)
    }
