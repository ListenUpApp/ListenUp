package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.InboxGlyphTile
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.ContentShapes
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_held_cannot_play
import listenup.composeapp.generated.resources.admin_held_for_review
import listenup.composeapp.generated.resources.admin_held_hidden_from_members
import listenup.composeapp.generated.resources.admin_release
import listenup.composeapp.generated.resources.chapter_editor_title
import listenup.composeapp.generated.resources.common_edit
import listenup.composeapp.generated.resources.metadata_match_on_audible
import org.jetbrains.compose.resources.stringResource

/**
 * Book Detail's triage section for a book held for review: what it is ("Held for review · Hidden
 * from all members."), why there is no Play ("It can’t be played until you release it."), and the
 * only things a held book allows (spec §8, §10): Release (filled, primary: it finishes the triage),
 * Edit (outlined), and Match and Edit chapters (text buttons, secondary) — fixing a new book's
 * metadata is the point of triage. Canvas `A-Detail-Held`; the second row is §10's.
 *
 * @param isReleasing a release is in flight: Release shows its spinner, the rest wait
 * @param onReleaseClick asks first — the caller shows [com.calypsan.listenup.client.features.admin.inbox.ReleaseToEveryoneDialog]
 * @param onEditClick opens Book Edit, where choosing collections also releases the book
 * @param onMatchClick opens the metadata match — the overflow menu's "Match metadata" route
 * @param onEditChaptersClick opens the chapter editor — the overflow menu's "Edit chapters" route
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
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InboxGlyphTile(size = 40.dp, corner = 14.dp, glyphSize = 22.dp)
                Column {
                    Text(
                        text = stringResource(Res.string.admin_held_for_review),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(Res.string.admin_held_hidden_from_members),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(Res.string.admin_held_cannot_play),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ListenUpButton(
                    text = stringResource(Res.string.admin_release),
                    onClick = onReleaseClick,
                    isLoading = isReleasing,
                    leadingIcon = Icons.Filled.Check,
                    modifier = Modifier.weight(1f),
                )
                ListenUpButton(
                    text = stringResource(Res.string.common_edit),
                    onClick = onEditClick,
                    enabled = !isReleasing,
                    filled = false,
                    leadingIcon = Icons.Outlined.Edit,
                    modifier = Modifier.weight(1f),
                )
            }
            // Secondary (spec §10): the overflow menu's two metadata fixes, in its own words.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryTriageAction(
                    text = stringResource(Res.string.metadata_match_on_audible),
                    icon = Icons.Outlined.Search,
                    enabled = !isReleasing,
                    onClick = onMatchClick,
                    modifier = Modifier.weight(1f),
                )
                SecondaryTriageAction(
                    text = stringResource(Res.string.chapter_editor_title),
                    icon = Icons.Outlined.FormatListNumbered,
                    enabled = !isReleasing,
                    onClick = onEditChaptersClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * A secondary triage action: an M3 text button (the lowest emphasis, below Edit's outline), with the
 * same press haptic every [ListenUpButton] gives and the overflow menu's glyph.
 */
@Composable
private fun SecondaryTriageAction(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    TextButton(
        onClick = {
            haptics.press()
            onClick()
        },
        enabled = enabled,
        modifier = modifier,
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(text = text)
    }
}
