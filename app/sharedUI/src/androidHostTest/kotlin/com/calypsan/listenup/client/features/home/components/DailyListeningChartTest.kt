package com.calypsan.listenup.client.features.home.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.home.HomeActions
import com.calypsan.listenup.client.features.home.HomeBody
import com.calypsan.listenup.client.features.home.HomeSelection
import com.calypsan.listenup.client.features.home.HomeWidth
import com.calypsan.listenup.client.features.home.homeReady
import com.calypsan.listenup.client.features.home.testAppHeader
import com.calypsan.listenup.client.features.home.weekWithStreak
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.client.testing.AtFontScale
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Spec §2.6: "7 bars oldest → Today (labelled)". The last bar says Today rather than its weekday letter, and on the
 * narrowest phone at 1.3× text the word still lands whole inside the chart, clear of the bar beside it.
 */
@Config(qualifiers = "w360dp-h800dp")
// Native graphics: the fit is a measurement of real glyph widths.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class DailyListeningChartTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var measurer: TextMeasurer
    private lateinit var density: Density

    private fun home(fontScale: Float) =
        composeRule.setContent {
            AtFontScale(fontScale) {
                measurer = rememberTextMeasurer()
                density = LocalDensity.current
                MaterialTheme {
                    HomeBody(
                        state = homeReady(slot = HomeSlot.Absent, everythingInProgress = emptyList()),
                        stats = weekWithStreak(),
                        width = HomeWidth.Compact,
                        actions = HomeActions.None,
                        selection = HomeSelection.None,
                        appHeader = testAppHeader,
                        contentPadding = PaddingValues(top = 24.dp, bottom = 136.dp),
                    )
                }
            }
        }

    private fun drawnLabels(): List<String> =
        composeRule
            .onNodeWithTag(HOME_STATS_CHART_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(WeekChartDayLabels)
            .orEmpty()

    @Test
    fun `the last bar is labelled Today`() {
        home(fontScale = 1f)
        val labels = drawnLabels()
        labels shouldHaveSize 7
        labels.last() shouldBe "Today"
        labels.dropLast(1).none { it == "Today" } shouldBe true
    }

    @Test
    fun `Today fits under its bar on a narrow phone at 1_3x text`() {
        home(fontScale = 1.3f)
        val labels = drawnLabels()
        val chartWidth =
            composeRule
                .onNodeWithTag(HOME_STATS_CHART_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .size.width
                .toFloat()
        val (barWidth, spacing) =
            with(density) {
                weekChartBarWidth(
                    chartWidth = chartWidth,
                    barCount = labels.size,
                    barSpacing = WeekChartBarSpacing.toPx(),
                    minBarWidth = 12.dp.toPx(),
                ) to
                    WeekChartBarSpacing.toPx()
            }
        val widths =
            labels.map { label ->
                measurer
                    .measure(label, TextStyle(fontSize = WeekChartLabelSize))
                    .size.width
                    .toFloat()
            }
        val spans =
            weekChartLabelSpans(labelWidths = widths, chartWidth = chartWidth, barWidth = barWidth, barSpacing = spacing)

        val today = spans.last()
        withClue("Today spans $today in a ${chartWidth}px chart") {
            today.start shouldBeGreaterThanOrEqual 0f
            today.endInclusive shouldBeLessThanOrEqual chartWidth
        }
        withClue("Today ${spans.last()} against the bar before it ${spans[spans.size - 2]}") {
            spans[spans.size - 2].endInclusive shouldBeLessThan today.start
        }
    }
}
