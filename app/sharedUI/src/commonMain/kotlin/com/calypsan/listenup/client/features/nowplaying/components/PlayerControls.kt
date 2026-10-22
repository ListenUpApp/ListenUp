package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.auth_sign_in
import listenup.composeapp.generated.resources.common_inbox
import listenup.composeapp.generated.resources.player_close
import listenup.composeapp.generated.resources.player_pause
import listenup.composeapp.generated.resources.player_play
import listenup.composeapp.generated.resources.player_retry
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The glyph and label for a primary that is a way out rather than play/pause, or null for play/pause/busy. */
internal fun PrimaryControl.wayOut(): Pair<ImageVector, StringResource>? =
    when (this) {
        PrimaryControl.Retry -> Icons.Rounded.Refresh to Res.string.player_retry

        PrimaryControl.SignIn -> Icons.AutoMirrored.Rounded.Login to Res.string.auth_sign_in

        PrimaryControl.OpenInbox -> Icons.Rounded.Inbox to Res.string.common_inbox

        PrimaryControl.Close -> Icons.Rounded.Close to Res.string.player_close

        PrimaryControl.Pause, PrimaryControl.Play, PrimaryControl.Busy, PrimaryControl.ListenAgain,
        PrimaryControl.TapToPlay,
        -> null
    }

/**
 * Round transparent icon-button.
 *
 * @param icon The icon vector to display.
 * @param contentDescription Accessibility label.
 * @param onClick Click callback.
 * @param size Touch-target diameter.
 * @param iconSize The glyph's size — the control's visual size, so a compact bar can keep a small
 * glyph on a full 48dp target. Half of [size] by default.
 * @param tint Icon colour.
 * @param pressFeedback Whether tapping fires
 * [com.calypsan.listenup.client.design.haptics.Haptics.press]. Defaults to true because every
 * transport use of this button *does* something (skip, chapter step); pass false for chrome that
 * merely navigates, like the docked bar's expand control — you should feel a transport press, not
 * a trip to another screen.
 */
@Composable
fun Ctrl(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp = 60.dp,
    iconSize: Dp = size * 0.5f,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    pressFeedback: Boolean = true,
) {
    val haptics = LocalHaptics.current
    IconButton(
        onClick = {
            if (pressFeedback) haptics.press()
            onClick()
        },
        modifier = Modifier.size(size),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
    }
}
