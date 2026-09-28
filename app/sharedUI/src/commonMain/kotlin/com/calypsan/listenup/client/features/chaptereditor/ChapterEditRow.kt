package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.ChapterTimeFormat
import com.calypsan.listenup.client.design.compactTouchTarget
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.domain.model.Chapter
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_delete_chapter
import listenup.composeapp.generated.resources.chapter_editor_edit_time
import listenup.composeapp.generated.resources.chapter_editor_insert_below
import listenup.composeapp.generated.resources.chapter_editor_lock
import listenup.composeapp.generated.resources.chapter_editor_more_for
import listenup.composeapp.generated.resources.chapter_editor_now_playing
import listenup.composeapp.generated.resources.chapter_editor_nudge_back
import listenup.composeapp.generated.resources.chapter_editor_nudge_forward
import listenup.composeapp.generated.resources.chapter_editor_play_from_here
import listenup.composeapp.generated.resources.chapter_editor_rename_title
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
 * @param menu what the row's overflow menu offers: rename, insert below, play from here, delete.
 * @param onEditTime type the start exactly — the time itself is the control (spec §7.4).
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
    menu: ChapterRowMenuActions,
    onEditTime: () -> Unit,
    modifier: Modifier = Modifier,
    isLocked: Boolean = false,
    nudgeStepMs: Long = COARSE_NUDGE_MS,
) {
    val colors = MaterialTheme.colorScheme
    val onRow = if (isSelected) colors.onPrimaryContainer else colors.onSurface

    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
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
                val editTimeLabel = stringResource(Res.string.chapter_editor_edit_time)
                Text(
                    // Precise rather than a rounded clock: this is the number being edited, and a
                    // start that reads the same before and after a nudge makes the nudge look broken.
                    text = ChapterTimeFormat.precise(chapter.startTime),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) colors.onPrimaryContainer else colors.primary,
                    // One line, always: a start split as "0:04:36." over "9" reads as two numbers.
                    maxLines = 1,
                    softWrap = false,
                    // The time is its own control: tap it to type the start to the millisecond.
                    modifier =
                        Modifier
                            // The start time's own hit shape, so its ripple reads as a control, not the row.
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable(onClickLabel = editTimeLabel, onClick = onEditTime)
                            .semantics { contentDescription = editTimeLabel }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                )
                if (isPlaying) NowBadge()
            }
        }

        // Their own row, packed: each control already has a 48dp touch target, and the row's 10dp
        // gap between five of them took the width the start time needs to show in full.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChapterRowActions(
                isLocked = isLocked,
                nudgeStepMs = nudgeStepMs,
                onNudge = onNudge,
                onSnapToPlayhead = onSnapToPlayhead,
                onToggleLock = onToggleLock,
            )
            ChapterOverflowMenu(chapter = chapter, menu = menu)
        }
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
) {
    val haptics = LocalHaptics.current
    val colors = MaterialTheme.colorScheme

    RowAction(Icons.Outlined.Remove, stringResource(Res.string.chapter_editor_nudge_back)) {
        haptics.press()
        onNudge(-nudgeStepMs)
    }
    RowAction(Icons.Outlined.Add, stringResource(Res.string.chapter_editor_nudge_forward)) {
        haptics.press()
        onNudge(nudgeStepMs)
    }
    RowAction(Icons.Outlined.MyLocation, stringResource(Res.string.chapter_editor_snap_to_playhead)) {
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
}

/**
 * What a chapter row's overflow menu offers — the set the spec gives every row (§7.4). Keyed by
 * chapter id, so one instance serves the whole list.
 *
 * @property onRename retitle the chapter (the caller asks for the new title).
 * @property onInsertBelow add a boundary after this one.
 * @property onPlayFromHere start playback at this chapter; null while another book (or nothing) is
 *   loaded, because playing from here then would replace what the listener has going, unasked.
 * @property onDelete remove the boundary (the caller confirms first).
 */
@Immutable
class ChapterRowMenuActions(
    val onRename: (String) -> Unit,
    val onInsertBelow: (String) -> Unit,
    val onPlayFromHere: ((String) -> Unit)?,
    val onDelete: (String) -> Unit,
)

/**
 * The row's overflow: an anchored menu from a named overflow button, the Material shape for a
 * short list of actions on one item. Delete is in the error colour; it still confirms, because the
 * caller routes it to the delete dialog.
 */
@Composable
private fun ChapterOverflowMenu(
    chapter: Chapter,
    menu: ChapterRowMenuActions,
) {
    val haptics = LocalHaptics.current
    var expanded by remember { mutableStateOf(false) }
    val choose: (() -> Unit) -> Unit = { action ->
        haptics.press()
        expanded = false
        action()
    }

    Box {
        RowAction(Icons.Outlined.MoreVert, stringResource(Res.string.chapter_editor_more_for, chapter.title)) {
            haptics.press()
            expanded = true
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.chapter_editor_rename_title)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = { choose { menu.onRename(chapter.id) } },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.chapter_editor_insert_below)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) },
                onClick = { choose { menu.onInsertBelow(chapter.id) } },
            )
            // Disabled rather than absent while another book (or nothing) is loaded, so the menu
            // keeps one shape and the action is discoverable.
            val onPlayFromHere = menu.onPlayFromHere
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.chapter_editor_play_from_here)) },
                leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                enabled = onPlayFromHere != null,
                onClick = { choose { onPlayFromHere?.invoke(chapter.id) } },
            )
            val destructive = MaterialTheme.colorScheme.error
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.chapter_editor_delete_chapter), color = destructive) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = destructive) },
                onClick = { choose { menu.onDelete(chapter.id) } },
            )
        }
    }
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
    // A 36dp footprint keeps five tools beside the start time; each still takes a full 48dp touch.
    IconButton(onClick = onClick, modifier = Modifier.compactTouchTarget(footprint = ACTION_SIZE)) {
        Icon(imageVector = icon, contentDescription = description, tint = tint)
    }
}
