package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.domain.chapter.ChapterOutlineRow
import com.calypsan.listenup.client.domain.chapter.withOpenParts
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReaders
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReadership
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
 *
 * Everyone else reading sits on their chapter's row ([readership]); a closed part's header carries everyone inside it,
 * and any other header the readers of the chapter folded into it.
 */
@Composable
internal fun BookChapterOutline(
    grouped: GroupedChapters,
    chapters: List<ChapterUiModel>,
    expanded: Boolean,
    playback: ChapterPlayback,
    readership: ChapterReadership = ChapterReadership.None,
) {
    var overrides by rememberSaveable(stateSaver = partOverridesSaver) { mutableStateOf(emptyMap<String, Boolean>()) }
    val rows = if (expanded) grouped.rows.withOpenParts(overrides) else grouped.previewRows
    // A header's own chapter has a row of its own unless it folded into the header.
    val chapterIdsWithRows =
        remember(grouped) {
            grouped.rows.mapNotNullTo(mutableSetOf()) { row -> (row as? ChapterOutlineRow.ChapterRow)?.run { chapter.id } }
        }
    val foldedReaders = { part: ChapterOutlineRow.PartHeader ->
        if (part.chapterId in chapterIdsWithRows) null else readership.onChapter(part.chapterId)
    }

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
                        val isOpen = row.isOpen(overrides)
                        CollapsiblePartRow(
                            part = row,
                            isOpen = isOpen,
                            onToggle = { overrides = row.toggled(overrides) },
                            playback = playback,
                            // Closed, the part's chapters have no rows: its header carries everyone in it.
                            readers = if (isOpen) foldedReaders(row) else readership.inPart(row.chapterId),
                            modifier = inset,
                        )
                    } else {
                        PlayablePartRow(part = row, playback = playback, readers = foldedReaders(row), modifier = inset)
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
                            readers = readership.onChapter(chapter.id),
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
    readers: ChapterReaders?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val summary = partSummary(part)
    val state = stringResource(if (isOpen) Res.string.common_expanded else Res.string.common_collapsed)
    val description = withReaders("${part.title}, $summary", readers)

    PartSurface(part, modifier) { ringColor ->
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
                            contentDescription = description
                            stateDescription = state
                        }.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isOpen) ListenUpIcons.ExpandMoreFilled else ListenUpIcons.KeyboardArrowRightFilled,
                    contentDescription = null,
                )
                Spacer(Modifier.size(8.dp))
                PartText(
                    part = part,
                    summary = summary,
                    current = playback.current.takeIf { part.isCurrentChapter },
                    modifier = Modifier.weight(1f),
                )
                readers?.let { ChapterReaderStack(it, ringColor, Modifier.padding(start = 12.dp)) }
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
    readers: ChapterReaders?,
    modifier: Modifier = Modifier,
) {
    PartSurface(part, modifier) { ringColor ->
        Row(
            modifier = Modifier.heightIn(min = PartRowMinHeight).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                PartText(
                    part = part,
                    summary = partSummary(part),
                    current = playback.current.takeIf { part.isCurrentChapter },
                    modifier = Modifier.weight(1f),
                )
                readers?.let { here ->
                    // The row is not one button, so the readers are read as a line of their own.
                    val sentence = chapterReadersSentence(here)
                    Box(Modifier.padding(start = 12.dp).clearAndSetSemantics { contentDescription = sentence }) {
                        ChapterReaderStack(here, ringColor)
                    }
                }
            }
            PartPlayButton(part, playback)
        }
    }
}

/** The header's container; [content] gets its colour, which the readers' avatars are cut out of. */
@Composable
private fun PartSurface(
    part: ChapterOutlineRow.PartHeader,
    modifier: Modifier,
    content: @Composable (containerColor: Color) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val containerColor = if (part.isCurrentChapter) colors.primaryContainer else colors.surfaceContainerHigh
    Surface(
        color = containerColor,
        contentColor = if (part.isCurrentChapter) colors.onPrimaryContainer else colors.onSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        content(containerColor)
    }
}

@Composable
private fun PartText(
    part: ChapterOutlineRow.PartHeader,
    summary: String,
    current: CurrentChapter?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(vertical = 8.dp)) {
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
            imageVector = ListenUpIcons.PlayArrowFilled,
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
