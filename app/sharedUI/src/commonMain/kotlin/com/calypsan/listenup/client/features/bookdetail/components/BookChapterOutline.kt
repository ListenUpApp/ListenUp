package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow
import com.calypsan.listenup.client.domain.chapter.withOpenParts
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.client.presentation.bookdetail.CurrentChapter
import com.calypsan.listenup.client.presentation.bookdetail.GroupedChapters
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_chapter_count
import listenup.composeapp.generated.resources.book_detail_chapter_count_one
import listenup.composeapp.generated.resources.book_detail_part_play_a11y
import listenup.composeapp.generated.resources.book_detail_part_summary
import listenup.composeapp.generated.resources.common_collapsed
import listenup.composeapp.generated.resources.common_expanded
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/** A part header's minimum height: the board's 60 dp, with a line for what it holds. */
private val PartRowMinHeight = 60.dp

/** How far a book's parts and chapters sit inside its header. */
private val BookTierInset = 12.dp

/** What a part row and a chapter row both need to play. */
internal data class ChapterPlayback(
    val current: CurrentChapter?,
    val canPlay: Boolean,
    val onPlayFromChapter: (Long) -> Unit,
)

/**
 * A marked book's chapters: the collapsed window with the part it sits in named above it, or with
 * [expanded] the whole outline, parts opening and closing. Which rows exist is the outline's answer,
 * so this only draws: a chapter row's number indexes [chapters].
 *
 * How the reader left each part lives here, on the screen, as nothing about it is worth keeping.
 */
@Composable
internal fun BookChapterOutline(
    grouped: GroupedChapters,
    chapters: List<ChapterUiModel>,
    expanded: Boolean,
    playback: ChapterPlayback,
) {
    var overrides by rememberSaveable(stateSaver = partOverridesSaver) { mutableStateOf(emptyMap<String, Boolean>()) }
    val rows = if (expanded) grouped.rows.withOpenParts(overrides) else grouped.previewRows

    if (!expanded) grouped.previewLabel?.let { PartLabel(it) }

    // A book is an outer tier: everything after its header sits inside it.
    var inBook = false
    rows.forEach { row ->
        val inset = if (inBook) Modifier.padding(start = BookTierInset) else Modifier
        when (row) {
            is ChapterOutlineRow.BookHeader -> {
                inBook = true
                key("book-${row.chapterId}") { BookHeaderRow(row.title) }
            }

            is ChapterOutlineRow.PartHeader -> {
                key("part-${row.chapterId}") {
                    if (expanded && row.chapterCount > 0) {
                        CollapsiblePartRow(
                            part = row,
                            isOpen = row.isOpen(overrides),
                            onToggle = { overrides = row.toggled(overrides) },
                            playback = playback,
                            modifier = inset,
                        )
                    } else {
                        PlayablePartRow(part = row, playback = playback, modifier = inset)
                    }
                }
            }

            is ChapterOutlineRow.ChapterRow -> {
                chapters.getOrNull(row.number - 1)?.let { chapter ->
                    key(chapter.id) {
                        ChapterRow(
                            chapter = chapter,
                            number = row.number,
                            current = playback.current.takeIf { chapter.isCurrent },
                            canPlay = playback.canPlay,
                            onPlayFromChapter = playback.onPlayFromChapter,
                            modifier = inset,
                        )
                    }
                }
            }
        }
    }
}

/** How the reader left each part, kept across a rotation. */
private val partOverridesSaver =
    mapSaver<Map<String, Boolean>>(
        save = { it },
        restore = { saved -> saved.mapValues { (_, isOpen) -> isOpen == true } },
    )

/** The part the window starts inside, whose own header is out of view. */
@Composable
private fun PartLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).semantics { heading() },
    )
}

/** A Book marker: a heading. It holds parts rather than folding them away. */
@Composable
private fun BookHeaderRow(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(horizontal = 8.dp).padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
    )
}

/**
 * A part in "All chapters": the row opens and closes it, and its play is a control of its own, so
 * neither has to be reached through the other.
 */
@Composable
private fun CollapsiblePartRow(
    part: ChapterOutlineRow.PartHeader,
    isOpen: Boolean,
    onToggle: () -> Unit,
    playback: ChapterPlayback,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val summary = partSummary(part)
    val state = stringResource(if (isOpen) Res.string.common_expanded else Res.string.common_collapsed)

    PartSurface(part, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = PartRowMinHeight)
                        .clickable(role = Role.Button) {
                            haptics.press()
                            onToggle()
                        }.semantics {
                            contentDescription = "${part.title}, $summary"
                            stateDescription = state
                        }.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isOpen) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
                Spacer(Modifier.size(8.dp))
                PartText(part, summary, playback.current.takeIf { part.isCurrentChapter })
            }
            PartPlayButton(part, playback)
        }
    }
}

/** A part with nothing to open: the collapsed list's, or one that is only its announcement. */
@Composable
private fun PlayablePartRow(
    part: ChapterOutlineRow.PartHeader,
    playback: ChapterPlayback,
    modifier: Modifier = Modifier,
) {
    PartSurface(part, modifier) {
        Row(
            modifier = Modifier.heightIn(min = PartRowMinHeight).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f)) {
                PartText(part, partSummary(part), playback.current.takeIf { part.isCurrentChapter })
            }
            PartPlayButton(part, playback)
        }
    }
}

@Composable
private fun PartSurface(
    part: ChapterOutlineRow.PartHeader,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (part.isCurrentChapter) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (part.isCurrentChapter) colors.onPrimaryContainer else colors.onSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        content = content,
    )
}

@Composable
private fun PartText(
    part: ChapterOutlineRow.PartHeader,
    summary: String,
    current: CurrentChapter?,
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            text = part.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = summary, style = MaterialTheme.typography.bodySmall)
        current?.let { here -> Text(text = currentChapterLabel(here), style = MaterialTheme.typography.labelMedium) }
    }
}

/** Starts the part at its opening chapter, which may be the one folded into this header. */
@Composable
private fun PartPlayButton(
    part: ChapterOutlineRow.PartHeader,
    playback: ChapterPlayback,
) {
    val haptics = LocalHaptics.current
    IconButton(
        onClick = {
            haptics.press()
            playback.onPlayFromChapter(part.startMs)
        },
        enabled = playback.canPlay,
        modifier = Modifier.semantics { selected = part.isCurrentChapter },
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = stringResource(Res.string.book_detail_part_play_a11y, part.title),
            tint = if (playback.canPlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "13 chapters · 3h 42m", or just the length of a part that is only its announcement. */
@Composable
private fun partSummary(part: ChapterOutlineRow.PartHeader): String {
    val length = DurationFormatter.hoursMinutes(part.durationMs.milliseconds)
    return when (part.chapterCount) {
        0 -> {
            length
        }

        1 -> {
            stringResource(
                Res.string.book_detail_part_summary,
                stringResource(Res.string.book_detail_chapter_count_one),
                length,
            )
        }

        else -> {
            stringResource(
                Res.string.book_detail_part_summary,
                stringResource(Res.string.book_detail_chapter_count, part.chapterCount),
                length,
            )
        }
    }
}
