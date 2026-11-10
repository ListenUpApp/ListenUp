package com.calypsan.listenup.client.features.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_retry
import listenup.composeapp.generated.resources.startup_setup_check_failed_message
import listenup.composeapp.generated.resources.startup_setup_check_failed_title
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen error surface shown when an admin's library-setup check fails.
 *
 * One surface for both checks that can fail: the startup readiness gate's and the setup wizard's
 * own. The admin gets an honest, retryable failure instead of an empty shell or a spinner that
 * never ends. [onRetry] re-runs the setup-status check.
 */
@Composable
fun SetupCheckFailedScreen(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = ListenUpIcons.ErrorOutlineFilled,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(Res.string.startup_setup_check_failed_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(Res.string.startup_setup_check_failed_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            ListenUpButton(text = stringResource(Res.string.common_retry), onClick = onRetry)
        }
    }
}
