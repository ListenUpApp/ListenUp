package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import com.calypsan.listenup.client.presentation.bookdetail.CurrentChapter
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_all_chapters
import listenup.composeapp.generated.resources.book_detail_fewer_chapters
import listenup.composeapp.generated.resources.book_detail_now_playing
import listenup.composeapp.generated.resources.book_detail_now_playing_in
import listenup.composeapp.generated.resources.book_detail_play_from_chapter_a11y
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/** A chapter row's minimum height: the board's 56 dp, above M3's 48 dp touch target. */
private val ChapterRowMinHeight = 56.dp

/**
 * Chapters that play from the chapter they name (spec §2.5). Collapsed, [preview] (the 5-row window around the current
 * chapter, or the first five); "All N chapters" expands in place to [chapters], "Fewer chapters" collapses it. Every row is a button; the current row
 * sits on the primary container and says how far in it is. Rows are disabled with the primary when the book can't play.
 *
 * Not lazy, on purpose (plan Decision 13): the section is one item of the page, so the social line's scroll target has
 * a fixed index, and a long book composes its rows once, on "All".
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BookChaptersSection(
    chapters: List<ChapterUiModel>,
    preview: List<ChapterUiModel>,
    currentChapter: CurrentChapter?,
    canPlay: Boolean,
    onPlayFromChapter: (startMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) chapters else preview
    // A chapter's number is its place in the whole book, not in the window: computed once per list.
    val numberById = remember(chapters) { chapters.withIndex().associate { (i, chapter) -> chapter.id to i + 1 } }
    // The same card as About and Readers, so the sections read as one family.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = ContentShapes.card,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChaptersHeader(
                chapterCount = chapters.size,
                modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp),
            )
            shown.forEach { chapter ->
                ChapterRow(
                    chapter = chapter,
                    number = numberById[chapter.id] ?: 0,
                    current = currentChapter.takeIf { chapter.isCurrent },
                    canPlay = canPlay,
                    onPlayFromChapter = onPlayFromChapter,
                )
            }
            // Expands in place, and collapses again.
            if (chapters.size > preview.size) {
                FilledTonalButton(
                    onClick = { expanded = !expanded },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = ChapterRowMinHeight),
                ) {
                    Text(
                        if (expanded) {
                            stringResource(Res.string.book_detail_fewer_chapters)
                        } else {
                            stringResource(Res.string.book_detail_all_chapters, chapters.size)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ChapterUiModel,
    number: Int,
    current: CurrentChapter?,
    canPlay: Boolean,
    onPlayFromChapter: (Long) -> Unit,
) {
    val haptics = LocalHaptics.current
    val label = stringResource(Res.string.book_detail_play_from_chapter_a11y, number, chapter.title)
    val isCurrent = chapter.isCurrent
    val containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val contentColor =
        if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val play = {
        haptics.press()
        onPlayFromChapter(chapter.startMs)
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = if (isCurrent) RoundedCornerShape(26.dp) else MaterialTheme.shapes.medium,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ChapterRowMinHeight)
                .clickable(enabled = canPlay, role = Role.Button, onClickLabel = label, onClick = play)
                // One button, named by what it does: "Play from chapter 6: Bridge Four".
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    selected = isCurrent
                    if (canPlay) {
                        onClick(label) {
                            play()
                            true
                        }
                    } else {
                        disabled()
                    }
                },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.widthIn(min = 22.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    text = chapter.title,
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                current?.let { here ->
                    Text(text = nowPlayingLabel(here.elapsedInChapterMs), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(text = chapter.duration, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** "Now playing · 12m in"; under a minute in there is nothing to count yet, so just "Now playing". */
@Composable
private fun nowPlayingLabel(elapsedInChapterMs: Long): String =
    if (elapsedInChapterMs < MILLIS_PER_MINUTE) {
        stringResource(Res.string.book_detail_now_playing)
    } else {
        stringResource(Res.string.book_detail_now_playing_in, DurationFormatter.hoursMinutes(elapsedInChapterMs.milliseconds))
    }

private const val MILLIS_PER_MINUTE = 60_000L
