@file:Suppress("MagicNumber")
@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.features.home.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.domain.DayBucket
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 7-day bar chart showing daily listening time.
 *
 * Draws bars using Compose Canvas — no external charting library needed.
 * Renders one bar per [DayBucket], where index 0 is today and index 6 is
 * six days ago, so the chart always fills exactly 7 columns.
 *
 * @param dailyBuckets 7-element list of day buckets, index 0 = today.
 * @param modifier Modifier from parent
 */
@Composable
fun DailyListeningChart(
    dailyBuckets: List<DayBucket>,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()

    // Map dayOffset → day-of-week label. Today (offset 0) is the rightmost bar.
    val chartData =
        remember(dailyBuckets) {
            val today =
                Clock.System
                    .now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .date

            // Buckets are today-first (index 0 = today), so reverse for left-to-right display.
            dailyBuckets.reversed().map { bucket ->
                val date = today.minus(bucket.dayOffsetFromToday, DateTimeUnit.DAY)
                ChartBar(
                    label = date.dayOfWeek.narrow(),
                    minutes = bucket.totalSeconds / 60f,
                )
            }
        }

    val maxMinutes = chartData.maxOf { it.minutes }.coerceAtLeast(1f)
    val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor)

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(100.dp),
    ) {
        val labelHeight = 16.dp.toPx()
        val chartHeight = size.height - labelHeight - 4.dp.toPx()
        val barCount = chartData.size
        val barSpacing = 8.dp.toPx()
        val totalSpacing = barSpacing * (barCount - 1)
        val barWidth = ((size.width - totalSpacing) / barCount).coerceAtLeast(12.dp.toPx())

        chartData.forEachIndexed { index, bar ->
            val x = index * (barWidth + barSpacing)
            val barHeight = bar.minutes / maxMinutes * chartHeight
            val barTop = chartHeight - barHeight

            // Draw bar with rounded top corners
            if (barHeight > 0f) {
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, barTop),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }

            // Draw day label centered below bar
            val labelResult = textMeasurer.measure(bar.label, labelStyle)
            val labelX = x + (barWidth - labelResult.size.width) / 2
            val labelY = chartHeight + 4.dp.toPx()
            drawText(labelResult, topLeft = Offset(labelX, labelY))
        }
    }
}

private data class ChartBar(
    val label: String,
    val minutes: Float,
)

/**
 * Narrow day-of-week label (single character).
 */
private fun DayOfWeek.narrow(): String =
    when (this) {
        DayOfWeek.MONDAY -> "M"
        DayOfWeek.TUESDAY -> "T"
        DayOfWeek.WEDNESDAY -> "W"
        DayOfWeek.THURSDAY -> "T"
        DayOfWeek.FRIDAY -> "F"
        DayOfWeek.SATURDAY -> "S"
        DayOfWeek.SUNDAY -> "S"
    }
