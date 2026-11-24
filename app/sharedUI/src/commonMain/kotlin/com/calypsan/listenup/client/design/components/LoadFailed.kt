package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_try_again
import org.jetbrains.compose.resources.stringResource

/**
 * A whole screen whose first load failed: the failure in one centred sentence, then **Try again**.
 *
 * The screen-sized counterpart of [InlineRetry] — for a screen with nothing else to show, where the only
 * recovery used to be leaving and coming back. The sentence is a polite live region so TalkBack hears it.
 * Immersive screens without a top bar also need a way back beside it.
 *
 * @param message The failure, already localized (typically `AppError.localized()`).
 * @param onRetry Re-runs the screen's load.
 */
@Composable
fun LoadFailed(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = Spacing.screenMargin),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Button(
                onClick = {
                    haptics.press()
                    onRetry()
                },
            ) {
                Text(stringResource(Res.string.common_try_again))
            }
        }
    }
}
