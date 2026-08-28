package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.ChapterTimeFormat
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.domain.model.Chapter
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_lock
import listenup.composeapp.generated.resources.chapter_editor_more
import listenup.composeapp.generated.resources.chapter_editor_now_playing
import listenup.composeapp.generated.resources.chapter_editor_nudge_back
import listenup.composeapp.generated.resources.chapter_editor_nudge_forward
import listenup.composeapp.generated.resources.chapter_editor_snap_to_playhead
import listenup.composeapp.generated.resources.chapter_editor_unlock
import org.jetbrains.compose.resources.stringResource

/**
 * Nudge steps from the spec: a second for ordinary correction, a tenth when placing a boundary
 * against something you can hear. Exposed so the row's ± buttons and the screen's arrow keys
 * cannot disagree about what one press is worth.
 */
const val COARSE_NUDGE_MS = 1_000L

/** The fine step, for a boundary being placed by ear. */
const val FINE_NUDGE_MS = 100L

private val ROW_SHAPE = RoundedCornerShape(16.dp)
private val NUMBER_COLUMN_WIDTH = 32.dp
private val ACTION_SIZE = 36.dp

/**
 * One chapter in the editor's list: its number, title, start, and everything you can do to it.
 *
 * The spec is explicit that the whole correction must be completable from this list alone — the
 * timeline is "optional spatial sugar". So every precision tool has a keyboard-and-tap equivalent
 * here: nudge for a known step, snap for the exact millisecond by ear, lock to exempt a boundary
 * from drift. A user who cannot drag, or will not, is not a second-class user of this screen.
 *
 * @param chapter the boundary to draw.
 * @param number its 1-based position **in the whole book**, which is simply its index in the
 *   start-time-ordered set — retiming re-sorts, so the number follows on its own. It is a
 *   parameter only because a row cannot see its own index.
 *
 *   Pass the index in the FULL set, not in whatever the list is currently showing: the search
 *   field filters the visible rows, and numbering those would relabel chapter 213 as chapter 1
 *   the moment someone typed into it.
 * @param isSelected whether the list and the detail lane are both focused on this row.
 * @param isPlaying whether the transport is currently inside this chapter.
 * @param onSelect focus this row.
 * @param onNudge move the start by a signed step; the caller decides coarse (1s) or fine (0.1s).
 * @param onSnapToPlayhead take the playhead's exact millisecond as this chapter's start.
 * @param onToggleLock pin or unpin against drift correction.
 * @param onMore open the overflow: delete, insert below, play from here.
 * @param modifier Modifier for the row.
 */
@Composable
fun ChapterEditRow(
    chapter: Chapter,
    number: Int,
    isSelected: Boolean,
    isPlaying: Boolean,
    onSelect: () -> Unit,
    onNudge: (Long) -> Unit,
    onSnapToPlayhead: () -> Unit,
    onToggleLock: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    isLocked: Boolean = false,
    nudgeStepMs: Long = COARSE_NUDGE_MS,
) {
    val colors = MaterialTheme.colorScheme
    val onRow = if (isSelected) colors.onPrimaryContainer else colors.onSurface

    Row(
        modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(if (isSelected) colors.primaryContainer else colors.surface)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "$number",
            modifier = Modifier.width(NUMBER_COLUMN_WIDTH),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) colors.onPrimaryContainer else colors.onSurfaceVariant,
        )

        Column(Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                color = onRow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    // Precise rather than a rounded clock: this is the number being edited, and a
                    // start that reads the same before and after a nudge makes the nudge look broken.
                    text = ChapterTimeFormat.precise(chapter.startTime),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) colors.onPrimaryContainer else colors.primary,
                )
                if (isPlaying) NowBadge()
            }
        }

        ChapterRowActions(
            isLocked = isLocked,
            nudgeStepMs = nudgeStepMs,
            onNudge = onNudge,
            onSnapToPlayhead = onSnapToPlayhead,
            onToggleLock = onToggleLock,
            onMore = onMore,
        )
    }
}

/**
 * The per-row tools, split out so the row itself reads as number, title, actions.
 *
 * Every one of these is a precision instrument with no drag involved — the spec's requirement that
 * the whole correction be completable from the list alone lives here.
 */
@Composable
private fun ChapterRowActions(
    isLocked: Boolean,
    nudgeStepMs: Long,
    onNudge: (Long) -> Unit,
    onSnapToPlayhead: () -> Unit,
    onToggleLock: () -> Unit,
    onMore: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val colors = MaterialTheme.colorScheme

    RowAction(Icons.Filled.Remove, stringResource(Res.string.chapter_editor_nudge_back)) {
        haptics.press()
        onNudge(-nudgeStepMs)
    }
    RowAction(Icons.Filled.Add, stringResource(Res.string.chapter_editor_nudge_forward)) {
        haptics.press()
        onNudge(nudgeStepMs)
    }
    RowAction(Icons.Filled.MyLocation, stringResource(Res.string.chapter_editor_snap_to_playhead)) {
        haptics.press()
        onSnapToPlayhead()
    }
    RowAction(
        icon = if (isLocked) Icons.Filled.Lock else Icons.Outlined.LockOpen,
        description =
            stringResource(
                if (isLocked) Res.string.chapter_editor_unlock else Res.string.chapter_editor_lock,
            ),
        tint = if (isLocked) colors.primary else colors.onSurfaceVariant,
        onClick = onToggleLock,
    )
    RowAction(Icons.Filled.MoreVert, stringResource(Res.string.chapter_editor_more), onClick = onMore)
}

/** "NOW" — the chapter the transport is currently inside. */
@Composable
private fun NowBadge() {
    Box(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        Text(
            text = stringResource(Res.string.chapter_editor_now_playing),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun RowAction(
    icon: ImageVector,
    description: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(ACTION_SIZE)) {
        Icon(imageVector = icon, contentDescription = description, tint = tint)
    }
}
