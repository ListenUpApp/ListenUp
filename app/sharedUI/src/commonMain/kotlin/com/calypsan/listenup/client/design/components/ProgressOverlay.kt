package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_percent
import org.jetbrains.compose.resources.stringResource

/**
 * Progress pill for book covers — a floating, inset capsule with a coral percentage chip and the
 * optional time-remaining text, over the shared [CoverScrim] recipe so the text clears WCAG AA
 * over the lightest cover. Sits at the bottom of a cover, and grows rather than clips when the
 * user's font scale is large.
 *
 * @param progress Progress value from 0.0 to 1.0
 * @param timeRemaining Optional formatted time remaining string (e.g., "2h 15m left")
 * @param modifier Optional modifier
 */
@Composable
fun ProgressOverlay(
    progress: Float,
    timeRemaining: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(8.dp)
                .heightIn(min = 34.dp)
                .clip(CircleShape)
                .background(CoverScrimDefaults.color)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
            Box(
                modifier = Modifier.heightIn(min = 24.dp).padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.common_percent, (progress.coerceIn(0f, 1f) * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        if (timeRemaining != null) {
            Text(
                text = timeRemaining,
                style = MaterialTheme.typography.labelMedium,
                color = CoverScrimDefaults.ContentColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
