package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_percent
import org.jetbrains.compose.resources.stringResource

/** Scrim behind the progress pill — a translucent dark wash standing in for the design's blur. */
private val ProgressScrim = Color.Black.copy(alpha = 0.42f)

/**
 * Progress pill for book covers — a floating, inset capsule with a coral percentage chip and the
 * optional time-remaining text, over a translucent dark scrim. Sits at the bottom of a cover.
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
                .height(34.dp)
                .clip(CircleShape)
                .background(ProgressScrim)
                .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
            Box(
                modifier = Modifier.height(24.dp).padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.common_percent, (progress.coerceIn(0f, 1f) * 100).toInt()),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        if (timeRemaining != null) {
            Text(
                text = timeRemaining,
                fontSize = 12.5.sp,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
