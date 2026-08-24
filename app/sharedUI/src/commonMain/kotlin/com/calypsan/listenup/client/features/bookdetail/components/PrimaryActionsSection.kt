package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.design.LocalDeviceContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.features.bookdetail.DownloadButton
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_play
import listenup.composeapp.generated.resources.book_detail_preparing
import listenup.composeapp.generated.resources.book_detail_unavailable_offline

/**
 * Primary action buttons - Play dominates, Download alongside.
 *
 * @param isWaitingForWifi True when download is queued but waiting for WiFi connection.
 *                         Passed to DownloadButton to show "Waiting for WiFi" state.
 * @param isPreparing True while a play request for this book is in flight (already debounced
 *                    upstream against a fast prepare — see [PlaybackManager.preparingBookIdUi]).
 *                    Swaps the play icon for a small indeterminate spinner so the tap isn't
 *                    met with silence during the seconds a prepare can take.
 */
@Composable
fun PrimaryActionsSection(
    downloadStatus: BookDownloadStatus,
    onPlayClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    isWaitingForWifi: Boolean = false,
    playEnabled: Boolean = true,
    downloadEnabled: Boolean = true,
    requestFocus: Boolean = false,
    onPlayDisabledClick: () -> Unit = {},
    showServerWarning: Boolean = false,
    isPreparing: Boolean = false,
) {
    val haptics = LocalHaptics.current
    val focusRequester = FocusRequester()

    if (requestFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Primary Play Button — left pill of the connected group: large outer corners, small inner corners
        Button(
            onClick = {
                // Only the live Play path gets the press — a disabled tap merely explains itself.
                if (playEnabled) {
                    haptics.press()
                    onPlayClick()
                } else {
                    onPlayDisabledClick()
                }
            },
            modifier =
                Modifier
                    .weight(2f)
                    .height(64.dp)
                    .then(if (requestFocus) Modifier.focusRequester(focusRequester) else Modifier),
            shape =
                RoundedCornerShape(
                    topStart = 32.dp,
                    bottomStart = 32.dp,
                    topEnd = 6.dp,
                    bottomEnd = 6.dp,
                ),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor =
                        if (playEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        },
                    contentColor =
                        if (playEnabled) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                ),
            elevation =
                ButtonDefaults.buttonElevation(
                    defaultElevation = if (playEnabled) 4.dp else 0.dp,
                    pressedElevation = if (playEnabled) 8.dp else 0.dp,
                ),
        ) {
            PlayButtonContent(offline = !playEnabled && showServerWarning, isPreparing = isPreparing)
        }

        // Download Button — right pill of the connected group: small inner corners, large outer corners
        if (LocalDeviceContext.current.supportsDownloads) {
            DownloadButton(
                status = downloadStatus,
                onDownloadClick = onDownloadClick,
                onCancelClick = onCancelClick,
                onDeleteClick = onDeleteClick,
                modifier = Modifier.size(64.dp),
                isWaitingForWifi = isWaitingForWifi,
                enabled = downloadEnabled,
                shape =
                    RoundedCornerShape(
                        topStart = 6.dp,
                        bottomStart = 6.dp,
                        topEnd = 32.dp,
                        bottomEnd = 32.dp,
                    ),
            )
        }
    }
}

/** Icon + label content for the Play button. Extracted to keep [PrimaryActionsSection] below complexity threshold. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayButtonContent(
    offline: Boolean,
    isPreparing: Boolean,
) {
    if (isPreparing) {
        // Same M3 Expressive wavy indicator as the player's PlayPauseFab (PlayerControls.kt) —
        // one canonical "busy" affordance across the play surfaces.
        CircularWavyProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = LocalContentColor.current,
            trackColor = LocalContentColor.current.copy(alpha = 0.24f),
        )
    } else {
        Icon(
            imageVector = if (offline) Icons.Default.CloudOff else Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
    }
    Spacer(modifier = Modifier.width(8.dp))
    Text(
        // The Button merges descendant semantics into one accessible node, and the Icon above
        // carries no contentDescription (decorative) — so this Text is the button's ENTIRE
        // announced name. Swapping it to "Preparing…" here is what stops a screen reader from
        // saying "Play" for the whole multi-second window a prepare can take.
        text =
            when {
                isPreparing -> stringResource(Res.string.book_detail_preparing)
                offline -> stringResource(Res.string.book_detail_unavailable_offline)
                else -> stringResource(Res.string.book_detail_play)
            },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
}
