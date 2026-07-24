package com.calypsan.listenup.client.features.shell.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.presentation.connection.ConnectionHealthUi
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_dismiss
import listenup.composeapp.generated.resources.shell_session_lapsed_body
import listenup.composeapp.generated.resources.shell_session_lapsed_sign_in
import listenup.composeapp.generated.resources.shell_session_lapsed_title
import listenup.composeapp.generated.resources.shell_update_available_body
import listenup.composeapp.generated.resources.shell_update_available_title
import org.jetbrains.compose.resources.stringResource

/**
 * Shell-level connection-health banner. Renders one pill per non-[ConnectionHealthUi.Hidden]
 * state: session-lapse ("Signed out — sign in to sync" + Sign-in) and contract-version-skew
 * ("Update available" + Dismiss). Deliberately has no unreachable-server state — offline-first
 * means there's no ambient "offline" banner; connectivity surfaces only at point of need (book
 * detail, player), not as shell chrome.
 *
 * Hard rules (spec §10): never modal, never blocks navigation/playback/browse; no
 * auto-navigation on state entry — the only navigation is the user tapping Sign in.
 * Dismissal is UI-local and resets when the state next changes (`remember(state)`); the
 * [ConnectionHealthUi.Outdated] case additionally offers a persisted dismissal via [onDismiss].
 * Visual language follows the book-detail OfflineBanner (color-container pill).
 */
@Composable
fun ConnectionHealthBanner(
    state: ConnectionHealthUi,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dismissed by remember(state) { mutableStateOf(false) }
    if (dismissed) return

    when (state) {
        ConnectionHealthUi.Hidden -> {
            return
        }

        ConnectionHealthUi.SessionExpired -> {
            BannerPill(
                modifier = modifier,
                container = MaterialTheme.colorScheme.errorContainer,
                onContainer = MaterialTheme.colorScheme.onErrorContainer,
                icon = Icons.Default.AccountCircle,
                title = stringResource(Res.string.shell_session_lapsed_title),
                body = stringResource(Res.string.shell_session_lapsed_body),
                actionLabel = stringResource(Res.string.shell_session_lapsed_sign_in),
                onAction = onSignIn,
                onClose = { dismissed = true },
            )
        }

        is ConnectionHealthUi.Outdated -> {
            BannerPill(
                modifier = modifier,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                onContainer = MaterialTheme.colorScheme.onTertiaryContainer,
                icon = Icons.Default.Info,
                title = stringResource(Res.string.shell_update_available_title),
                body =
                    stringResource(
                        Res.string.shell_update_available_body,
                        state.clientVersion,
                        state.serverVersion,
                    ),
                actionLabel = stringResource(Res.string.common_dismiss),
                onAction = onDismiss,
                onClose = null,
            )
        }
    }
}

/**
 * Shared pill scaffold for [ConnectionHealthBanner]'s visible states: a rounded [Surface] with a
 * leading icon, title/body text column, a primary action [Button], and an optional trailing
 * dismiss [IconButton] ([onClose] is `null` when the primary action already is the dismissal).
 */
@Composable
private fun BannerPill(
    container: Color,
    onContainer: Color,
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = container,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = onContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.85f),
                )
            }
            Button(
                onClick = onAction,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = onContainer,
                        contentColor = container,
                    ),
            ) {
                Text(actionLabel)
            }
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(Res.string.common_dismiss),
                        tint = onContainer,
                    )
                }
            }
        }
    }
}
