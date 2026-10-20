package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.theme.extendedColors
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_held_for_review
import listenup.composeapp.generated.resources.book_detail_held_body
import listenup.composeapp.generated.resources.book_detail_held_release
import listenup.composeapp.generated.resources.chapter_editor_title
import listenup.composeapp.generated.resources.library_menu_edit_details
import listenup.composeapp.generated.resources.match_menu_item
import org.jetbrains.compose.resources.stringResource

/** The Release button's height: the board's tall primary, beside the stacked Edit details tile. */
private val ReleaseMinHeight = 72.dp

/** The second row's buttons. */
private val SecondaryMinHeight = 56.dp

/** Edit details' tile width: the pencil above its label. */
private val EditTileWidth = 96.dp

/**
 * Book Detail's triage panel for a book held for review (spec §3.2.3 Held), where the action unit would be. Amber, not
 * coral: the fixed held roles ([com.calypsan.listenup.client.design.theme.ListenUpExtendedColors.held]), the app's
 * "waiting for you" colour, which dynamic colour never turns into the wallpaper's tertiary. What it is ("Held for review"), one sentence of why ("Hidden from every member, and it can’t be
 * played until you release it."), then the only things a held book allows: **Release to members** (asks first), Edit
 * details, Match details and Edit chapters. One row each, short labels (the M3E ButtonGroup call).
 *
 * @param isReleasing a release is in flight: Release shows its spinner, the rest wait
 * @param onReleaseClick asks first — the caller shows [com.calypsan.listenup.client.features.admin.inbox.ReleaseToEveryoneDialog]
 * @param onEditClick opens Book Edit, where choosing collections also releases the book
 * @param onMatchClick opens Match details
 * @param onEditChaptersClick opens the chapter editor
 */
@Suppress("LongParameterList")
@Composable
fun HeldForReviewSection(
    isReleasing: Boolean,
    onReleaseClick: () -> Unit,
    onEditClick: () -> Unit,
    onMatchClick: () -> Unit,
    onEditChaptersClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = ContentShapes.card,
        color = MaterialTheme.extendedColors.heldContainer,
        contentColor = MaterialTheme.extendedColors.onHeldContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = stringResource(Res.string.admin_held_for_review),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(Res.string.book_detail_held_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ReleaseButton(
                    isReleasing = isReleasing,
                    onClick = onReleaseClick,
                    modifier = Modifier.weight(1f).heightIn(min = ReleaseMinHeight),
                )
                TriageTonalButton(
                    text = stringResource(Res.string.library_menu_edit_details),
                    icon = Icons.Outlined.Edit,
                    enabled = !isReleasing,
                    stacked = true,
                    onClick = onEditClick,
                    modifier = Modifier.width(EditTileWidth).heightIn(min = ReleaseMinHeight),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TriageTonalButton(
                    text = stringResource(Res.string.match_menu_item),
                    icon = Icons.Outlined.Search,
                    enabled = !isReleasing,
                    onClick = onMatchClick,
                    modifier = Modifier.weight(1f).heightIn(min = SecondaryMinHeight),
                )
                TriageTonalButton(
                    text = stringResource(Res.string.chapter_editor_title),
                    icon = Icons.Outlined.FormatListNumbered,
                    enabled = !isReleasing,
                    onClick = onEditChaptersClick,
                    modifier = Modifier.weight(1f).heightIn(min = SecondaryMinHeight),
                )
            }
        }
    }
}

/** Release to members, filled in the amber `tertiary`; its spinner replaces the label while a release is in flight. */
@Composable
private fun ReleaseButton(
    isReleasing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    Button(
        onClick = {
            haptics.press()
            onClick()
        },
        enabled = !isReleasing,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.extendedColors.held,
                contentColor = MaterialTheme.extendedColors.onHeld,
                disabledContainerColor = MaterialTheme.extendedColors.held,
                disabledContentColor = MaterialTheme.extendedColors.onHeld,
            ),
        modifier = modifier.testTag("heldRelease"),
    ) {
        if (isReleasing) {
            ListenUpLoadingIndicatorSmall(color = MaterialTheme.extendedColors.onHeld)
        } else {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(Res.string.book_detail_held_release))
        }
    }
}

/** A tonal triage action on the panel's lowest surface, with the press haptic and the menu's glyph. */
@Suppress("LongParameterList")
@Composable
private fun TriageTonalButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
) {
    val haptics = LocalHaptics.current
    FilledTonalButton(
        onClick = {
            haptics.press()
            onClick()
        },
        enabled = enabled,
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        contentPadding = if (stacked) ButtonDefaults.SmallContentPadding else ButtonDefaults.ContentPadding,
        modifier = modifier,
    ) {
        if (stacked) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(text = text, style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(text = text)
        }
    }
}
