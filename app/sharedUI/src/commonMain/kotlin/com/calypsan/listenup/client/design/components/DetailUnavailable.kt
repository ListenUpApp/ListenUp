package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.design.theme.Spacing
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource

/**
 * An immersive screen (no top bar — its back button lives in a hero that never loaded) that failed to load:
 * the failure, a way back, and **Try again** when retrying can change anything.
 *
 * @param message The failure, as the screen's state carries it.
 * @param onBack Leaves the screen.
 * @param onRetry Re-runs the screen's load; null when nothing can (the failure is live state, not a fetch).
 */
@Composable
fun DetailUnavailable(
    message: String,
    onBack: () -> Unit,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (onRetry != null) {
            LoadFailed(message = message, onRetry = onRetry)
        } else {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = Spacing.screenMargin),
            )
        }
        FloatingBackButton(onBackClick = onBack, modifier = Modifier.align(Alignment.TopStart))
    }
}

/**
 * The back button an immersive screen shows while it has no hero of its own to carry one — loading, not
 * found, or failed. Clears the status bar.
 */
@Composable
fun FloatingBackButton(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onBackClick,
        modifier =
            modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(8.dp),
    ) {
        Icon(
            imageVector = ListenUpIcons.ArrowBack,
            contentDescription = stringResource(Res.string.common_back),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
