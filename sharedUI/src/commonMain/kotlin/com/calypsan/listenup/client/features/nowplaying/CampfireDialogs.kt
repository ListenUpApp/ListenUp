package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.campfire_rejoin_confirm
import listenup.composeapp.generated.resources.campfire_rejoin_dismiss
import listenup.composeapp.generated.resources.campfire_rejoin_message
import listenup.composeapp.generated.resources.campfire_rejoin_title
import listenup.composeapp.generated.resources.campfire_spoiler_cancel
import listenup.composeapp.generated.resources.campfire_spoiler_confirm
import listenup.composeapp.generated.resources.campfire_spoiler_message
import listenup.composeapp.generated.resources.campfire_spoiler_title
import org.jetbrains.compose.resources.stringResource

/**
 * Confirm dialog shown when [com.calypsan.listenup.client.presentation.campfire.CampfireScreenUiState.ConfirmingSpoiler]
 * is active — joining would move the caller's progress forward materially (co-listening design
 * spec §7, campfire implementation plan Task 10).
 *
 * @param onConfirm Applies the join (`CampfireViewModel.confirmSpoilerJoin`).
 * @param onCancel Declines and leaves the peeked session (`CampfireViewModel.cancelSpoilerJoin`).
 */
@Composable
fun CampfireSpoilerDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    ListenUpAlertDialog(
        onDismissRequest = onCancel,
        title = stringResource(Res.string.campfire_spoiler_title),
        text = stringResource(Res.string.campfire_spoiler_message),
        confirmText = stringResource(Res.string.campfire_spoiler_confirm),
        onConfirm = onConfirm,
        dismissText = stringResource(Res.string.campfire_spoiler_cancel),
        onDismiss = onCancel,
        icon = Icons.Default.LocalFireDepartment,
    )
}

/**
 * Confirm dialog shown when a rejoin finds the room has drifted more than the large-drift
 * threshold from local playback (`CampfireScreenUiState.Active.pendingRejoinSync`, co-listening
 * design spec §3.2/§9).
 *
 * @param onConfirm Jumps to the room's current position (`CampfireViewModel.confirmRejoinSync`).
 * @param onDismiss Keeps listening at the local (unsynced) position.
 */
@Composable
fun CampfireRejoinDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ListenUpAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.campfire_rejoin_title),
        text = stringResource(Res.string.campfire_rejoin_message),
        confirmText = stringResource(Res.string.campfire_rejoin_confirm),
        onConfirm = onConfirm,
        dismissText = stringResource(Res.string.campfire_rejoin_dismiss),
        onDismiss = onDismiss,
        icon = Icons.Default.Sync,
    )
}
