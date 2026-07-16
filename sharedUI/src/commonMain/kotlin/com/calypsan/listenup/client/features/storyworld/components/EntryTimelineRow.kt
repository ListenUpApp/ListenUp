package com.calypsan.listenup.client.features.storyworld.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.components.AnchorChip
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.story_world_delete_entry
import listenup.composeapp.generated.resources.story_world_delete_entry_title

private val CONNECTOR_TILE_SIZE = 30.dp
private val CONNECTOR_LINE_WIDTH = 2.dp
private val ROW_BOTTOM_SPACING = 20.dp

/**
 * A single row in an entity's chronological log timeline: a numbered leading circle connected to
 * the next row by a vertical line (omitted after the last entry), the entry's mention-resolved
 * text with its [AnchorChip] below, and a trailing overflow menu offering delete. Edit arrives
 * with the composer (Task 10) via an additional `onEditEntry` callback on this row's caller.
 *
 * @param index This entry's 1-based position in the timeline.
 * @param renderedText [com.calypsan.listenup.client.presentation.storyworld.EntityEntryRow.renderedText].
 * @param anchorLabel The resolved display string for this entry's anchor (see `anchorLabelText`).
 * @param isLast True for the last row in the list — suppresses the connector line below it.
 * @param onDelete Called when the viewer confirms deleting this entry.
 * @param modifier Modifier for the row.
 */
@Composable
fun EntryTimelineRow(
    index: Int,
    renderedText: String,
    anchorLabel: String,
    isLast: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(bottom = if (isLast) 0.dp else ROW_BOTTOM_SPACING),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxHeight()) {
            Box(
                modifier =
                    Modifier
                        .size(CONNECTOR_TILE_SIZE)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = index.toString(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                )
            }
            if (!isLast) {
                Box(
                    modifier =
                        Modifier
                            .padding(top = 4.dp)
                            .width(CONNECTOR_LINE_WIDTH)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = renderedText,
                fontSize = 15.5.sp,
                lineHeight = 23.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            AnchorChip(label = anchorLabel)
        }
        EntryOverflowMenu(onDelete = onDelete)
    }
}

/** Trailing overflow icon button — delete only for now; edit joins in the Task 10 composer wiring. */
@Composable
private fun EntryOverflowMenu(onDelete: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.story_world_delete_entry)) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    showDeleteConfirm = true
                },
            )
        }
    }

    if (showDeleteConfirm) {
        ListenUpDestructiveDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(Res.string.story_world_delete_entry_title),
            text = "",
            confirmText = stringResource(Res.string.common_delete),
            dismissText = stringResource(Res.string.common_cancel),
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
        )
    }
}
