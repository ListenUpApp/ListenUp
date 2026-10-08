@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.features.home.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.presentation.home.weekChartColumns
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
    chartHeight: Dp = 124.dp,
) {
    val todayColor = MaterialTheme.colorScheme.primary
    val barColor = MaterialTheme.colorScheme.primaryContainer
    val emptyColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()

    // Ordering, labels and which column is today all come from the shared projection, so this
    // chart and the web client cannot disagree about which bar is today.
    val chartData =
        remember(dailyBuckets) { weekChartColumns(dailyBuckets) }

    val maxSeconds = chartData.maxOf { it.totalSeconds }.coerceAtLeast(1L).toFloat()
    val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor)

    // Bars grow up from the baseline on screen entry, rippling left-to-right (today rises last).
    val growth = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Bespoke: a linear clock, not a spring; each bar reads its own staggered window of it below.
        growth.animateTo(targetValue = 1f, animationSpec = tween(durationMillis = 700, easing = LinearEasing))
    }

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(chartHeight),
    ) {
        val labelHeight = 16.dp.toPx()
        val plotHeight = size.height - labelHeight - 4.dp.toPx()
        val barCount = chartData.size
        val barSpacing = 8.dp.toPx()
        val totalSpacing = barSpacing * (barCount - 1)
        val barWidth = ((size.width - totalSpacing) / barCount).coerceAtLeast(12.dp.toPx())

        val stubSize = emptyDayStubSize(barWidth, EmptyStubMaxWidth.toPx(), EmptyStubHeight.toPx())
        // Each bar opens a little after the one to its left, so the row ripples up on entry.
        val barStagger = if (barCount > 1) (1f - BAR_GROW_FRACTION) / (barCount - 1) else 0f
        chartData.forEachIndexed { index, bar ->
            val x = index * (barWidth + barSpacing)
            val isToday = bar.isToday
            val isEmpty = bar.totalSeconds <= 0L
            // An empty day is a small stub on the baseline, so the row reads as days with nothing in
            // them — never a full-width circle that looks like listening.
            val markWidth = if (isEmpty) stubSize.width else barWidth
            val fullHeight = if (isEmpty) stubSize.height else bar.totalSeconds / maxSeconds * plotHeight
            val barProgress = ((growth.value - index * barStagger) / BAR_GROW_FRACTION).coerceIn(0f, 1f)
            val barHeight = fullHeight * LinearOutSlowInEasing.transform(barProgress)
            val barTop = plotHeight - barHeight
            // Today reads as the coral accent; on an empty today that is only the small stub, a quiet mark.
            val color =
                when {
                    isToday -> todayColor
                    isEmpty -> emptyColor
                    else -> barColor
                }

            // Fully-rounded "pill" bars for a more expressive chart.
            drawRoundRect(
                color = color,
                topLeft = Offset(x + (barWidth - markWidth) / 2f, barTop),
                size = Size(markWidth, barHeight),
                cornerRadius = CornerRadius(markWidth / 2f),
            )

            // Draw day label centered below bar
            val labelResult = textMeasurer.measure(bar.label, labelStyle)
            val labelX = x + (barWidth - labelResult.size.width) / 2
            val labelY = plotHeight + 4.dp.toPx()
            drawText(labelResult, topLeft = Offset(labelX, labelY))
        }
    }
}

/** How tall an empty day's stub stands on the baseline. */
private val EmptyStubHeight = 6.dp

/** The widest an empty day's stub grows, however wide its column. */
private val EmptyStubMaxWidth = 16.dp

/**
 * The stub an empty day draws: [stubHeight] tall and at most [stubMaxWidth] wide, narrowing with a
 * column thinner than that, so it stays a small mark on the baseline at every chart width.
 */
internal fun emptyDayStubSize(
    barWidth: Float,
    stubMaxWidth: Float,
    stubHeight: Float,
): Size = Size(minOf(barWidth, stubMaxWidth), minOf(stubHeight, barWidth))

/** Fraction of the entry animation each bar spends growing; the remainder is its stagger offset. */
private const val BAR_GROW_FRACTION = 0.6f
