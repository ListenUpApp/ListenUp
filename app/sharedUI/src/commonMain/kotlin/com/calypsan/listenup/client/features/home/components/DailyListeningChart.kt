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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.presentation.home.weekChartColumns
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_today
import org.jetbrains.compose.resources.stringResource

/** Test tag on the week chart's canvas; the caller supplies its text alternative. */
internal const val HOME_STATS_CHART_TAG = "home-stats-chart"

/**
 * The day labels the chart draws under its bars, oldest first. Canvas text is invisible to semantics, so the chart
 * states it here; it sits before the caller's modifier, where a `clearAndSetSemantics` cannot swallow it.
 */
internal val WeekChartDayLabels = SemanticsPropertyKey<List<String>>("WeekChartDayLabels")

/** The day labels' type size. */
internal val WeekChartLabelSize = 11.sp

/** The gap between neighbouring bars. */
internal val WeekChartBarSpacing = 8.dp

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

    // The last bar reads "Today" (spec §2.6), as iOS draws it; the others keep their weekday letter.
    val todayLabel = stringResource(Res.string.home_today)
    val dayLabels = chartData.map { if (it.isToday) todayLabel else it.label }
    val maxSeconds = chartData.maxOf { it.totalSeconds }.coerceAtLeast(1L).toFloat()
    val labelStyle = TextStyle(fontSize = WeekChartLabelSize, color = labelColor)

    // Bars grow up from the baseline on screen entry, rippling from the oldest day to today (today rises last).
    val growth = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Bespoke: a linear clock, not a spring; each bar reads its own staggered window of it below.
        growth.animateTo(targetValue = 1f, animationSpec = tween(durationMillis = 700, easing = LinearEasing))
    }

    Canvas(
        // The tag goes first: a caller's clearAndSetSemantics would otherwise swallow it.
        modifier =
            Modifier
                .testTag(HOME_STATS_CHART_TAG)
                .semantics { this[WeekChartDayLabels] = dayLabels }
                .then(modifier)
                .fillMaxWidth()
                .height(chartHeight),
    ) {
        val labelHeight = 16.dp.toPx()
        val plotHeight = size.height - labelHeight - 4.dp.toPx()
        val barCount = chartData.size
        val barSpacing = WeekChartBarSpacing.toPx()
        val barWidth =
            weekChartBarWidth(
                chartWidth = size.width,
                barCount = barCount,
                barSpacing = barSpacing,
                minBarWidth = MinBarWidth.toPx(),
            )
        val labelResults = dayLabels.map { textMeasurer.measure(it, labelStyle) }
        // A time axis mirrors with the layout (M3): in a right-to-left layout today stands at the left.
        val isRtl = layoutDirection == LayoutDirection.Rtl
        val labelSpans =
            weekChartLabelSpans(
                labelWidths = labelResults.map { it.size.width.toFloat() },
                chartWidth = size.width,
                barWidth = barWidth,
                barSpacing = barSpacing,
                isRtl = isRtl,
            )

        val stubSize = emptyDayStubSize(barWidth, EmptyStubMaxWidth.toPx(), EmptyStubHeight.toPx())
        // Each bar opens a little after the day before it, so the row ripples up on entry.
        val barStagger = if (barCount > 1) (1f - BAR_GROW_FRACTION) / (barCount - 1) else 0f
        chartData.forEachIndexed { index, bar ->
            val x =
                weekChartBarLeft(
                    index = index,
                    barWidth = barWidth,
                    barSpacing = barSpacing,
                    chartWidth = size.width,
                    isRtl = isRtl,
                )
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

            val labelY = plotHeight + 4.dp.toPx()
            drawText(labelResults[index], topLeft = Offset(labelSpans[index].start, labelY))
        }
    }
}

/** The narrowest a bar gets, however narrow the chart. */
private val MinBarWidth = 12.dp

/** Each bar's width: the chart's width shared across [barCount] bars and the gaps between them. */
internal fun weekChartBarWidth(
    chartWidth: Float,
    barCount: Int,
    barSpacing: Float,
    minBarWidth: Float,
): Float = ((chartWidth - barSpacing * (barCount - 1)) / barCount).coerceAtLeast(minBarWidth)

/**
 * Where bar [index] (0 the oldest day) starts, px from the chart's left: the oldest at the layout's start, today at its
 * end, so the row runs left to right, or right to left when [isRtl], as a time axis mirrors with its layout (M3). Pure.
 */
internal fun weekChartBarLeft(
    index: Int,
    barWidth: Float,
    barSpacing: Float,
    chartWidth: Float,
    isRtl: Boolean,
): Float {
    val fromStart = index * (barWidth + barSpacing)
    return if (isRtl) chartWidth - fromStart - barWidth else fromStart
}

/**
 * Where each day label is drawn, px from the chart's left, given each label's measured width: centred under its bar
 * ([weekChartBarLeft], so mirrored when [isRtl]), but never past either edge of the chart. "Today" under the last bar
 * outgrows a narrow phone's column at large text, so it slides inward to end flush with the chart rather than being
 * cut by the card.
 */
internal fun weekChartLabelSpans(
    labelWidths: List<Float>,
    chartWidth: Float,
    barWidth: Float,
    barSpacing: Float,
    isRtl: Boolean = false,
): List<ClosedFloatingPointRange<Float>> =
    labelWidths.mapIndexed { index, labelWidth ->
        val barLeft =
            weekChartBarLeft(
                index = index,
                barWidth = barWidth,
                barSpacing = barSpacing,
                chartWidth = chartWidth,
                isRtl = isRtl,
            )
        val centred = barLeft + (barWidth - labelWidth) / 2f
        val left = centred.coerceIn(0f, (chartWidth - labelWidth).coerceAtLeast(0f))
        left..(left + labelWidth)
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
