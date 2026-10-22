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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
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
import listenup.composeapp.generated.resources.book_detail_chapters
import listenup.composeapp.generated.resources.book_detail_held_match
import listenup.composeapp.generated.resources.common_edit
import listenup.composeapp.generated.resources.book_detail_held_body
import listenup.composeapp.generated.resources.book_detail_held_release
import listenup.composeapp.generated.resources.chapter_editor_title
import listenup.composeapp.generated.resources.library_menu_edit_details
import listenup.composeapp.generated.resources.match_menu_item
import org.jetbrains.compose.resources.stringResource

/** The Release button's height: the board's tall primary, the panel's one full-width action. */
private val ReleaseMinHeight = 72.dp

/** The second row's buttons: a glyph over one word. */
private val SecondaryMinHeight = 64.dp

/**
 * Book Detail's triage panel for a book held for review (spec §3.2.3 Held), where the action unit would be. Amber, not
 * coral: the fixed held roles ([com.calypsan.listenup.client.design.theme.ListenUpExtendedColors.held]), the app's
 * "waiting for you" colour, which dynamic colour never turns into the wallpaper's tertiary. What it is ("Held for review"), one sentence of why ("Hidden from every member, and it can’t be
 * played until you release it."), then the only things a held book allows: **Release to members** (asks first) across
 * the panel, then Edit details, Match details and Edit chapters in one row with short labels ("Edit", "Match",
 * "Chapters"), each named in full for TalkBack (the M3E ButtonGroup call: one row, shorter labels, never a wrap).
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
            ReleaseButton(
                isReleasing = isReleasing,
                onClick = onReleaseClick,
                modifier = Modifier.fillMaxWidth().heightIn(min = ReleaseMinHeight),
            )
            // One row, short labels (the M3E ButtonGroup rule): each a glyph over one word, named in full for TalkBack.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TriageTonalButton(
                    label = stringResource(Res.string.common_edit),
                    description = stringResource(Res.string.library_menu_edit_details),
                    icon = Icons.Outlined.Edit,
                    enabled = !isReleasing,
                    onClick = onEditClick,
                    modifier = Modifier.weight(1f),
                )
                TriageTonalButton(
                    label = stringResource(Res.string.book_detail_held_match),
                    description = stringResource(Res.string.match_menu_item),
                    icon = Icons.Outlined.Search,
                    enabled = !isReleasing,
                    onClick = onMatchClick,
                    modifier = Modifier.weight(1f),
                )
                TriageTonalButton(
                    label = stringResource(Res.string.book_detail_chapters),
                    description = stringResource(Res.string.chapter_editor_title),
                    icon = Icons.Outlined.FormatListNumbered,
                    enabled = !isReleasing,
                    onClick = onEditChaptersClick,
                    modifier = Modifier.weight(1f),
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

/** A tonal triage action on the panel's lowest surface: its glyph over one short word, with the press haptic. */
@Suppress("LongParameterList")
@Composable
private fun TriageTonalButton(
    label: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
        contentPadding = ButtonDefaults.SmallContentPadding,
        modifier = modifier.heightIn(min = SecondaryMinHeight).semantics { contentDescription = description },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            // The button's own description names it in full; the short word is for the eye.
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.semantics { hideFromAccessibility() },
            )
        }
    }
}
