package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_try_again
import org.jetbrains.compose.resources.stringResource

/**
 * One line saying what failed, then **Try again**, which restarts only that section.
 *
 * Spec §3.8: a section keeps its place while loading and says plainly when it failed; Home's sections use the
 * same pieces. The line is a polite live region (§8.2 status row), so TalkBack hears the failure without losing
 * its place; the button is at least 48dp tall. At large font scales the button wraps under the line.
 *
 * @param line The section's own sentence, e.g. "Couldn't load activity".
 * @param onRetry The section's restart.
 */
@Composable
fun InlineRetry(
    line: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = line,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(
            onClick = {
                haptics.press()
                onRetry()
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(Res.string.common_try_again))
        }
    }
}
