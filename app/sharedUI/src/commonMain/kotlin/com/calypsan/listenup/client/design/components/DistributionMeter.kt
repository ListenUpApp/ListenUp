package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ListenUpTheme

private const val METER_HEIGHT_DP = 12
private const val SWATCH_SIZE_DP = 10
private const val MIN_WEIGHT = 0.001f

/**
 * A single segment in a [DistributionMeter].
 *
 * @param label Human-readable name for this segment, shown in the legend below the bar.
 * @param weight Proportional weight of this segment relative to the total. Values ≤ 0 are floored
 *   to a small epsilon so zero-weight segments don't crash [Modifier.weight].
 * @param color Fill colour for both the bar segment and the legend swatch.
 */
data class MeterSegment(
    val label: String,
    val weight: Float,
    val color: Color,
)

/**
 * A horizontal proportional bar made of colour-coded segments separated by 2.dp gaps, followed by
 * a wrapping legend of the first [legendLimit] segments. Segments are scaled to their relative
 * [MeterSegment.weight]; zero-weight values are kept at a minimum epsilon so Compose's
 * [Modifier.weight] never receives 0.
 *
 * @param segments The list of segments to display. Empty lists are rendered as an empty bar.
 * @param modifier Modifier for the whole component column.
 * @param legendLimit Maximum number of segments to include in the legend (defaults to 4).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DistributionMeter(
    segments: List<MeterSegment>,
    modifier: Modifier = Modifier,
    legendLimit: Int = 4,
) {
    val total = segments.sumOf { it.weight.coerceAtLeast(MIN_WEIGHT).toDouble() }.toFloat()
    Column(modifier = modifier.fillMaxWidth()) {
        // Proportional bar
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(METER_HEIGHT_DP.dp)
                    .clip(RoundedCornerShape(6.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            segments.forEach { seg ->
                val safeWeight = seg.weight.coerceAtLeast(MIN_WEIGHT) / total
                Box(
                    modifier =
                        Modifier
                            .weight(safeWeight)
                            .fillMaxHeight()
                            .background(seg.color),
                )
            }
        }

        // Legend
        if (segments.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                segments.take(legendLimit).forEach { seg ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(SWATCH_SIZE_DP.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(seg.color),
                        )
                        Text(
                            text = seg.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun DistributionMeterPreview() {
    ListenUpTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            DistributionMeter(
                segments =
                    listOf(
                        MeterSegment("Fantasy", 45f, Color(0xFF2A6FDB)),
                        MeterSegment("Thriller", 30f, Color(0xFF7A5AF8)),
                        MeterSegment("Sci-Fi", 15f, Color(0xFF1F8A5B)),
                        MeterSegment("Romance", 10f, Color(0xFFC2562A)),
                    ),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
