package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_sheet_chapter_current
import listenup.composeapp.generated.resources.player_sheet_chapter_play_from
import listenup.composeapp.generated.resources.player_sheet_playing_left
import org.jetbrains.compose.resources.stringResource

/**
 * One row of the chapter list (spec §5.5.4, `Sheets-Android`): number · title · duration. The current chapter is filled
 * coral (`primary` / `onPrimary`, C14) and says "Playing · 8m left" under its title; there is no trailing glyph, because
 * the words say it. Screen readers hear what a tap does — "Play from chapter 7: Anything Reasonable, 33:18" — or, on
 * the current row, "Chapter 6, Bridge Four, now playing, 8m left" (§8), and the current row is selected.
 *
 * @param number The chapter's own number.
 * @param title The chapter's title, ellipsised when too long.
 * @param durationLabel The chapter's length ("33:18").
 * @param isCurrent Whether this is the chapter playing now.
 * @param playingLeft The current chapter's remainder ("8m"), read in this leaf so the tick recomposes only this row;
 *   null where no row is current.
 * @param onClick The row was tapped.
 */
@Composable
fun PlayerChapterRow(
    number: Int,
    title: String,
    durationLabel: String,
    isCurrent: Boolean,
    playingLeft: (() -> String)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val colors = MaterialTheme.colorScheme
    val container = if (isCurrent) colors.primary else Color.Transparent
    val ink = if (isCurrent) colors.onPrimary else colors.onSurface
    val quietInk = if (isCurrent) colors.onPrimary else colors.onSurfaceVariant
    val left = if (isCurrent) playingLeft?.invoke() else null
    val label =
        if (isCurrent && left != null) {
            stringResource(Res.string.player_sheet_chapter_current, number, title, left)
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
                Text(
                    text = stringResource(Res.string.player_sheet_playing_left, left),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = ink,
                )
            }
        }
        Text(text = durationLabel, style = MaterialTheme.typography.bodyMedium, color = quietInk)
    }
}

/** The board's row height; it clears the 48dp target with room for the "Playing · …" line. */
private val ROW_MIN_HEIGHT = 56.dp
