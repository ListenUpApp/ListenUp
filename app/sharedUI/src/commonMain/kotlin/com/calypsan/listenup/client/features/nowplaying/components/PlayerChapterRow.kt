package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.nowplaying.chapterLeftToTheMinute
import com.calypsan.listenup.client.features.nowplaying.shortDuration
import com.calypsan.listenup.client.features.nowplaying.spokenDuration
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_sheet_chapter_current
import listenup.composeapp.generated.resources.player_sheet_chapter_play_from
import listenup.composeapp.generated.resources.player_sheet_playing_left
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration

/**
 * One row of the chapter list (spec §5.5.4, `Sheets-Android`): number · title · duration. The current chapter is filled
 * coral (`primary` / `onPrimary`, C14) and says "Playing · 8m left" under its title; there is no trailing glyph, because
 * the words say it. Screen readers hear what a tap does — "Play from chapter 7: Anything Reasonable, 33:18" — or, on
 * the current row, "Chapter 6, Bridge Four, now playing, 8 minutes left" (§8): the screen's "8m" in words, so it is
 * never heard as "8 metres". The current row is selected.
 *
 * @param number The chapter's own number.
 * @param title The chapter's title, ellipsised when too long.
 * @param durationLabel The chapter's length ("33:18").
 * @param isCurrent Whether this is the chapter playing now.
 * @param playingLeft The current chapter's remainder, floored to the minute ([chapterLeftToTheMinute]), read in this
 *   leaf so the tick recomposes only this row; null where no row is current.
 * @param chapterFraction How far through the current chapter, 0..1, for the bar under its title (§5.3: linear, not
 *   wavy); null where no bar is drawn (the phone sheet). Read only while the bar draws, so a tick never recomposes the
 *   row. The bar is decorative: the row's label already says how long is left (§8).
 * @param onClick The row was tapped.
 */
@Composable
fun PlayerChapterRow(
    number: Int,
    title: String,
    durationLabel: String,
    isCurrent: Boolean,
    playingLeft: (() -> Duration)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    chapterFraction: (() -> Float)? = null,
) {
    val haptics = LocalHaptics.current
    val colors = MaterialTheme.colorScheme
    val container = if (isCurrent) colors.primary else Color.Transparent
    val ink = if (isCurrent) colors.onPrimary else colors.onSurface
    val quietInk = if (isCurrent) colors.onPrimary else colors.onSurfaceVariant
    val leftDuration = if (isCurrent) playingLeft?.invoke() else null
    val left = leftDuration?.let { shortDuration(it.inWholeMilliseconds) }
    val label =
        if (leftDuration != null) {
            stringResource(Res.string.player_sheet_chapter_current, number, title, spokenDuration(leftDuration))
        } else {
            stringResource(Res.string.player_sheet_chapter_play_from, number, title, durationLabel)
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .clip(MaterialTheme.shapes.large)
                .background(container)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    selected = isCurrent
                }.clickable(role = Role.Button) {
                    haptics.press()
                    onClick()
                }.padding(horizontal = Spacing.md, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = quietInk,
            modifier = Modifier.widthIn(min = 22.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style =
                    MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                    ),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (left != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (chapterFraction != null) ChapterRowBar(fraction = chapterFraction)
                    Text(
                        text = stringResource(Res.string.player_sheet_playing_left, left),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = ink,
                    )
                }
            }
        }
        Text(text = durationLabel, style = MaterialTheme.typography.bodyMedium, color = quietInk)
    }
}

/** The board's row height; it clears the 48dp target with room for the "Playing · …" line. */
private val ROW_MIN_HEIGHT = 56.dp

/** The current row's chapter bar's test tag. */
internal const val CHAPTER_ROW_BAR_TAG = "player-chapter-row-bar"

/**
 * The current row's chapter bar (spec §5.3, the board's 80 × 4dp): M3's linear progress indicator
 * (m3.material.io/components/progress-indicators), with no gap and no stop mark, as the board draws it. The fill is
 * `onPrimary`, the row's own ink; the track is `onPrimaryContainer`, darker than the board's pale `#FFDBD1` so the fill
 * reads against it at 3:1 or better (WCAG 1.4.11; plan C7). Silent to TalkBack: the row's label says the time left.
 */
@Composable
private fun ChapterRowBar(fraction: () -> Float) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier
                .size(width = 80.dp, height = 4.dp)
                .testTag(CHAPTER_ROW_BAR_TAG)
                .clearAndSetSemantics {},
    ) {
        LinearProgressIndicator(
            progress = fraction,
            modifier = Modifier.fillMaxSize(),
            color = colors.onPrimary,
            trackColor = colors.onPrimaryContainer,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}
