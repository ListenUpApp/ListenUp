package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import com.calypsan.listenup.client.design.components.TonalLabel
import com.calypsan.listenup.client.design.components.switchRow
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.theme.extendedColors
import com.calypsan.listenup.client.features.settings.byline
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverViewModel
import com.calypsan.listenup.client.presentation.hardcover.KeepOffRemoves
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.hardcover_book_row_change_match
import listenup.composeapp.generated.resources.hardcover_book_row_chosen_by_you
import listenup.composeapp.generated.resources.hardcover_book_row_just_matched
import listenup.composeapp.generated.resources.hardcover_book_row_matched_unnamed
import listenup.composeapp.generated.resources.hardcover_book_row_needs_match
import listenup.composeapp.generated.resources.hardcover_book_row_needs_match_detail
import listenup.composeapp.generated.resources.hardcover_book_sync_nothing_yet
import listenup.composeapp.generated.resources.hardcover_book_sync_removed
import listenup.composeapp.generated.resources.hardcover_book_sync_up_to_date
import listenup.composeapp.generated.resources.hardcover_book_sync_waiting
import listenup.composeapp.generated.resources.hardcover_find_on_hardcover
import listenup.composeapp.generated.resources.hardcover_keep_off_confirm_action
import listenup.composeapp.generated.resources.hardcover_keep_off_confirm_body
import listenup.composeapp.generated.resources.hardcover_keep_off_confirm_body_reads
import listenup.composeapp.generated.resources.hardcover_keep_off_confirm_body_to_read
import listenup.composeapp.generated.resources.hardcover_keep_off_confirm_title
import listenup.composeapp.generated.resources.hardcover_keep_off_switch
import listenup.composeapp.generated.resources.hardcover_kept_off_line
import listenup.composeapp.generated.resources.hardcover_never_matched_line
import listenup.composeapp.generated.resources.hardcover_match_remove
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import androidx.compose.ui.semantics.heading

private val CardCorner = 20.dp
private val StatusIconSize = 18.dp
private val SwitchGlyphSize = 20.dp
private val ActionMinHeight = 48.dp

/**
 * Book Detail's Hardcover card (spec B5). Draws nothing unless the user is connected. A book never matched is
 * the Sync with Hardcover switch and one quiet line saying it isn't matched yet; one that needs a match, is
 * matched, or is kept off adds its own block. A null [onFindMatch] hides it entirely, without asking Koin for its
 * ViewModel — desktop is frozen and has no Find on Hardcover.
 */
@Composable
fun BookHardcoverSection(
    bookId: String,
    onFindMatch: ((bookId: String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (onFindMatch == null) return
    BoundBookHardcoverSection(bookId = bookId, onFindMatch = onFindMatch, modifier = modifier)
}

/** [BookHardcoverContent] bound to its [BookHardcoverViewModel]. */
@Composable
private fun BoundBookHardcoverSection(
    bookId: String,
    onFindMatch: (bookId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookHardcoverViewModel = koinViewModel(parameters = { parametersOf(bookId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BookHardcoverContent(
        state = state,
        onFindMatch = { onFindMatch(bookId) },
        onRemoveMatch = viewModel::removeMatch,
        onSetSynced = viewModel::setSynced,
        modifier = modifier,
    )
}

/**
 * The card for [state], without its ViewModel. A tonal card on every width, headed by Sync with Hardcover
 * (#1541) as the canvas draws it. Switching off asks first only when [BookHardcoverUiState.Linked.keepOffRemoves]
 * says something visible would leave; the switch stays on behind the dialog until Keep off.
 */
@Composable
internal fun BookHardcoverContent(
    state: BookHardcoverUiState,
    onFindMatch: () -> Unit,
    onRemoveMatch: () -> Unit,
    onSetSynced: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == BookHardcoverUiState.Hidden) return
    var asking by rememberSaveable { mutableStateOf(false) }
    val removes = (state as? BookHardcoverUiState.Linked)?.keepOffRemoves
    if (asking && removes != null) {
        ListenUpAlertDialog(
            onDismissRequest = { asking = false },
            title = stringResource(Res.string.hardcover_keep_off_confirm_title),
            text = stringResource(removes.confirmBody()),
            confirmText = stringResource(Res.string.hardcover_keep_off_confirm_action),
            onConfirm = {
                asking = false
                onSetSynced(false)
            },
            dismissText = stringResource(Res.string.common_cancel),
            onDismiss = { asking = false },
        )
    }
    val isOn = state !is BookHardcoverUiState.KeptOff || state.isResuming
    Surface(
        shape = RoundedCornerShape(CardCorner),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SyncSwitchRow(
                isOn = isOn,
                onCheckedChange = { on -> if (!on && removes != null) asking = true else onSetSynced(on) },
            )
            when (state) {
                BookHardcoverUiState.Hidden -> {
                    Unit
                }

                // A book never matched says so quietly, so the switch's "on" doesn't read as "syncing" (#1562).
                BookHardcoverUiState.Unmatched -> {
                    Text(
                        stringResource(Res.string.hardcover_never_matched_line),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                BookHardcoverUiState.NeedsMatch -> {
                    HorizontalDivider()
                    NeedsMatchBody(onFindMatch)
                }

                is BookHardcoverUiState.Linked -> {
                    HorizontalDivider()
                    LinkedBody(state, onFindMatch, onRemoveMatch)
                }

                is BookHardcoverUiState.KeptOff -> {
                    if (!state.isResuming) {
                        Text(
                            stringResource(Res.string.hardcover_kept_off_line),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sync with Hardcover: the whole row is the switch — one TalkBack node named by its label, announced on or off,
 * with the toggle haptic ([switchRow]). The [Switch] is drawn with `onCheckedChange = null`, so it is only the
 * visual, carrying a check while on.
 */
@Composable
private fun SyncSwitchRow(
    isOn: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = LocalHaptics.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ActionMinHeight)
                .switchRow(checked = isOn, haptics = haptics, onCheckedChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isOn) Icons.Outlined.Link else Icons.Outlined.LinkOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(SwitchGlyphSize),
        )
        Text(
            stringResource(Res.string.hardcover_keep_off_switch),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = isOn,
            onCheckedChange = null,
            thumbContent =
                if (isOn) {
                    {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
        )
    }
}

/** The confirmation names exactly what leaves: deviation 5's three bodies. No `else`: a new kind must pick its words. */
private fun KeepOffRemoves.confirmBody(): StringResource =
    when (this) {
        KeepOffRemoves.READS -> Res.string.hardcover_keep_off_confirm_body_reads
        KeepOffRemoves.TO_READ -> Res.string.hardcover_keep_off_confirm_body_to_read
        KeepOffRemoves.READS_AND_TO_READ -> Res.string.hardcover_keep_off_confirm_body
    }

@Composable
private fun NeedsMatchBody(onFindMatch: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            stringResource(Res.string.hardcover_book_row_needs_match),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(Res.string.hardcover_book_row_needs_match_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    TonalAction(
        text = stringResource(Res.string.hardcover_find_on_hardcover),
        onClick = onFindMatch,
        icon = Icons.Outlined.Search,
    )
}

@Composable
private fun LinkedBody(
    state: BookHardcoverUiState.Linked,
    onFindMatch: () -> Unit,
    onRemoveMatch: () -> Unit,
) {
    val haptics = LocalHaptics.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            state.match.title ?: stringResource(Res.string.hardcover_book_row_matched_unnamed),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        byline(state.match.authors, state.match.releaseYear)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        SyncStatus(state)
        if (state.match.chosenByYou) TonalLabel(stringResource(Res.string.hardcover_book_row_chosen_by_you))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        TonalAction(text = stringResource(Res.string.hardcover_book_row_change_match), onClick = onFindMatch)
        TextButton(
            onClick = {
                haptics.press()
                onRemoveMatch()
            },
            modifier = Modifier.heightIn(min = ActionMinHeight),
        ) {
            Text(stringResource(Res.string.hardcover_match_remove), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Where the book stands — or "Matched just now" in its place for a minute after this device matched it. */
@Composable
private fun SyncStatus(state: BookHardcoverUiState.Linked) {
    val success = MaterialTheme.extendedColors.success
    val (icon, text, color) =
        if (state.justMatched) {
            Triple(Icons.Outlined.CheckCircle, Res.string.hardcover_book_row_just_matched, success)
        } else {
            state.sync.status(success)
        }
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(StatusIconSize))
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

// Deliberately no `else`: a new state must fail to compile here rather than borrow another's words.
@Composable
private fun HardcoverBookSync.status(success: Color): Triple<ImageVector, StringResource, Color> =
    when (this) {
        HardcoverBookSync.UP_TO_DATE -> {
            Triple(Icons.Outlined.CheckCircle, Res.string.hardcover_book_sync_up_to_date, success)
        }

        HardcoverBookSync.WAITING -> {
            Triple(
                Icons.Outlined.Sync,
                Res.string.hardcover_book_sync_waiting,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HardcoverBookSync.NOTHING_SENT_YET -> {
            Triple(
                Icons.Outlined.Schedule,
                Res.string.hardcover_book_sync_nothing_yet,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Amber and calm: nothing is lost, and listening again starts a new read.
        HardcoverBookSync.REMOVED_ON_HARDCOVER -> {
            Triple(
                Icons.Outlined.PauseCircle,
                Res.string.hardcover_book_sync_removed,
                MaterialTheme.colorScheme.tertiary,
            )
        }
    }

@Composable
private fun TonalAction(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    val haptics = LocalHaptics.current
    FilledTonalButton(
        onClick = {
            haptics.press()
            onClick()
        },
        modifier = Modifier.heightIn(min = ActionMinHeight),
        contentPadding =
            if (icon !=
                null
            ) {
                ButtonDefaults.ButtonWithIconContentPadding
            } else {
                ButtonDefaults.ContentPadding
            },
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}
