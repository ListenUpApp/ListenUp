package com.calypsan.listenup.client.features.library.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_edit
import listenup.composeapp.generated.resources.library_select_all
import listenup.composeapp.generated.resources.selection_add_to_collection
import listenup.composeapp.generated.resources.selection_add_to_shelf
import listenup.composeapp.generated.resources.selection_cancel_a11y
import listenup.composeapp.generated.resources.selection_n_selected
import org.jetbrains.compose.resources.stringResource

/** The floating toolbar's elevation: M3 level 3, the one shadow the selection bar casts (plan Q8). */
private val SelectionToolbarElevation = 6.dp

/** The header's height: a standard M3 top bar's. */
private val SelectionHeaderHeight = 64.dp

/** The smallest touch target an action may have. */
private val ActionMinHeight = 48.dp

/** The floating toolbar's own height: its 64dp container, grown to hold an icon over a label. */
private val SelectionToolbarHeight = 72.dp

/**
 * How much of a host's bottom edge the floating selection toolbar covers, above whatever the host
 * already clears (navigation bar, mini player): the toolbar and the gaps either side of it. A host
 * adds it to its content's bottom padding while selecting ([plusSelectionToolbar]), so the end of
 * its list can always scroll clear of the toolbar.
 */
val SelectionToolbarClearance: Dp = SelectionToolbarHeight + Spacing.md * 2

/** These paddings, with [SelectionToolbarClearance] added at the bottom while [isSelecting]. */
fun PaddingValues.plusSelectionToolbar(isSelecting: Boolean): PaddingValues =
    if (isSelecting) BottomExtended(this, SelectionToolbarClearance) else this

/** [base] with [extra] more at the bottom. */
private class BottomExtended(
    private val base: PaddingValues,
    private val extra: Dp,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = base.calculateLeftPadding(layoutDirection)

    override fun calculateTopPadding(): Dp = base.calculateTopPadding()

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        base.calculateRightPadding(layoutDirection)

    override fun calculateBottomPadding(): Dp = base.calculateBottomPadding() + extra
}

/** "Select all N" in the selection header: every book the screen shows, filtered as the reader sees it. */
data class SelectAllAction(
    val count: Int,
    val onSelectAll: () -> Unit,
)

/**
 * The selection bar, in two parts laid over the host's full-size `Box` (spec §6.1):
 * - a `primaryContainer` **header** at the top with **Cancel selection** (✕) and "N selected", which
 *   TalkBack announces as the count changes;
 * - a vibrant **floating toolbar** at the bottom (`primaryContainer` / `onPrimaryContainer`) holding
 *   the actions with full labels: Add to shelf, Add to collection (admin), Edit (admin, where routed).
 *
 * A null action callback hides its button rather than disabling it. An action that is drawn but
 * cannot act is worse than one that was never offered: the first reads as a broken app, the second
 * as a feature this screen does not carry.
 *
 * @param selectedCount Number of currently selected books
 * @param onAddToShelf Called when "Add to shelf" is tapped
 * @param onAddToCollection Called when "Add to collection" is tapped (null = hide button)
 * @param onEdit Called when "Edit" is tapped (null = hide button)
 * @param onClose Called when Cancel selection is tapped
 * @param modifier Optional modifier for the full-size layer holding both parts
 * @param visible Whether the bar is up; each part slides in from its own edge
 * @param topClearance Space above the header for what the host keeps clear at its top (the status
 *   bar), so the header sits where a host's own header would, as in Library
 * @param bottomClearance Space under the floating toolbar for what the host draws there (navigation
 *   bar, mini player), so the toolbar floats above it
 * @param selectAll "Select all N" at the header's end (null = this host offers none: Home, Discover)
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SelectionToolbar(
    selectedCount: Int,
    onAddToShelf: () -> Unit,
    onAddToCollection: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    bottomClearance: Dp = 0.dp,
    topClearance: Dp = 0.dp,
    selectAll: SelectAllAction? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = topClearance),
        ) {
            SelectionHeader(selectedCount = selectedCount, onClose = onClose, selectAll = selectAll)
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomClearance + Spacing.md),
        ) {
            HorizontalFloatingToolbar(
                expanded = true,
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                expandedShadowElevation = SelectionToolbarElevation,
                modifier = Modifier.testTag("selection-actions"),
            ) {
                SelectionActionButton(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = stringResource(Res.string.selection_add_to_shelf),
                    enabled = selectedCount > 0,
                    onClick = onAddToShelf,
                )
                if (onAddToCollection != null) {
                    SelectionActionButton(
                        icon = Icons.Outlined.CollectionsBookmark,
                        label = stringResource(Res.string.selection_add_to_collection),
                        enabled = selectedCount > 0,
                        onClick = onAddToCollection,
                    )
                }
                if (onEdit != null) {
                    SelectionActionButton(
                        icon = Icons.Default.Edit,
                        label = stringResource(Res.string.common_edit),
                        enabled = selectedCount > 0,
                        onClick = onEdit,
                    )
                }
            }
        }
    }
}

/** The top half: Cancel selection, the count and, where offered, Select all, on `primaryContainer`. */
@Composable
private fun SelectionHeader(
    selectedCount: Int,
    onClose: () -> Unit,
    selectAll: SelectAllAction?,
) {
    val haptics = LocalHaptics.current
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("selection-header"),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = SelectionHeaderHeight)
                    .padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            IconButton(
                onClick = {
                    haptics.press()
                    onClose()
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.selection_cancel_a11y),
                )
            }
            Text(
                text = stringResource(Res.string.selection_n_selected, selectedCount),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (selectAll != null) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        haptics.press()
                        selectAll.onSelectAll()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.heightIn(min = ActionMinHeight),
                ) {
                    Text(stringResource(Res.string.library_select_all, selectAll.count))
                }
            }
        }
    }
}

/**
 * One labelled action in the floating toolbar: its icon above its label, so the three full labels
 * fit a phone's width, at least the 48dp touch target. Colours come from the toolbar's
 * `onPrimaryContainer` content colour; a disabled action is muted by the button's own state.
 */
@Composable
private fun SelectionActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    TextButton(
        onClick = {
            haptics.press()
            onClick()
        },
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
        modifier = Modifier.heightIn(min = ActionMinHeight),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
