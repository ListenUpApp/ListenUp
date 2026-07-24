package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val UPCOMING_TRACK_ALPHA = 0.22f
private const val UPCOMING_LABEL_ALPHA = 0.6f
private val SEGMENT_HEIGHT = 6.dp

/**
 * The signature M3 Expressive wizard step tracker: a row of one rounded segment per step, where
 * completed and active steps fill with [accent] and upcoming steps sit at a faded [ink], with a
 * short label beneath each. Completed steps prefix their label with a check glyph.
 *
 * Designed to live inside a colour-blocked hero ([ColorBlockHero]) — pass the hero's foreground
 * [ink] colour so the labels read against the coloured chrome, and an [accent] for the filled
 * segments. Generic over any linear multi-step flow; the ABS import wizard composes it with its
 * Upload / Review / Apply / Done steps.
 *
 * @param steps Ordered step labels; one segment is rendered per entry.
 * @param currentStep Zero-based index of the in-progress step. Indices below it render as completed
 *   (filled + check), the index itself as active (filled), indices above as upcoming (faded).
 * @param modifier Modifier for the tracker column.
 * @param accent Fill colour for completed and active segments.
 * @param ink Foreground colour for labels and the faded upcoming-segment track.
 */
@Composable
fun WizardStepTracker(
    steps: List<String>,
    currentStep: Int,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    ink: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        steps.forEachIndexed { index, label ->
            val done = index < currentStep
            val active = index == currentStep
            StepSegment(
                label = label,
                reached = done || active,
                done = done,
                accent = accent,
                ink = ink,
            )
        }
    }
}

@Composable
private fun RowScope.StepSegment(
    label: String,
    reached: Boolean,
    done: Boolean,
    accent: Color,
    ink: Color,
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.Start,
    ) {
        Spacer(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(SEGMENT_HEIGHT)
                    .clip(CircleShape)
                    .background(if (reached) accent else ink.copy(alpha = UPCOMING_TRACK_ALPHA)),
        )
        Row(
            modifier = Modifier.padding(top = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (done) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = ink,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (reached) ink else ink.copy(alpha = UPCOMING_LABEL_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
            )
        }
    }
}
