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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.presentation.connection.ConnectionHealthUi
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_dismiss
import listenup.composeapp.generated.resources.shell_session_lapsed_body
import listenup.composeapp.generated.resources.shell_session_lapsed_sign_in
import listenup.composeapp.generated.resources.shell_session_lapsed_title
import org.jetbrains.compose.resources.stringResource

/**
 * Shell-level connection-health banner. Phase 1 renders only the session-lapse case:
 * "Signed out — sign in to sync" plus a Sign-in action and a dismiss affordance.
 *
 * Hard rules (spec §10): never modal, never blocks navigation/playback/browse; no
 * auto-navigation on state entry — the only navigation is the user tapping Sign in.
 * Dismissal is UI-local and resets when the state next changes (`remember(state)`).
 * Visual language follows the book-detail OfflineBanner (error-container pill).
 */
@Composable
fun ConnectionHealthBanner(
    state: ConnectionHealthUi,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dismissed by remember(state) { mutableStateOf(false) }
    if (state !is ConnectionHealthUi.SessionExpired || dismissed) return

    val container = MaterialTheme.colorScheme.errorContainer
    val onContainer = MaterialTheme.colorScheme.onErrorContainer
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
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null,
                tint = onContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.shell_session_lapsed_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                )
                Text(
                    text = stringResource(Res.string.shell_session_lapsed_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.85f),
                )
            }
            Button(
                onClick = onSignIn,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = onContainer,
                        contentColor = container,
                    ),
            ) {
                Text(stringResource(Res.string.shell_session_lapsed_sign_in))
            }
            IconButton(onClick = { dismissed = true }) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.common_dismiss),
                    tint = onContainer,
                )
            }
        }
    }
}
