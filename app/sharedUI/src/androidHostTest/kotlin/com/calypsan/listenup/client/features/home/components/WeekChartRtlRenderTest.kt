package com.calypsan.listenup.client.features.home.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.calypsan.listenup.client.design.text.ThemedFor
import com.calypsan.listenup.client.domain.DayBucket
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The drawn chart, not just its helpers: today's bar stands at the reader's end of the row in either direction. */
@Config(qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class WeekChartRtlRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Where today's colour sits across the chart, 0 (left edge) to 1 (right edge). */
    private fun todayAcross(rtl: Boolean): Float {
        var today = Color.Unspecified
        composeRule.mainClock.autoAdvance = true
        composeRule.setContent {
            ThemedFor(rtl) {
                today = MaterialTheme.colorScheme.primary
                // Only today has listening, so today's colour is drawn by its bar alone.
                DailyListeningChart(dailyBuckets = List(7) { DayBucket(it, if (it == 0) 3_600L else 0L) })
            }
        }
        composeRule.waitForIdle()
        val pixels = composeRule.onNodeWithTag(HOME_STATS_CHART_TAG).captureToImage().toPixelMap()
        val columns = (0 until pixels.width).filter { x -> (0 until pixels.height).any { y -> pixels[x, y] == today } }
        columns.size shouldBeGreaterThan 0
        return columns.average().toFloat() / pixels.width
    }

    @Test
    fun `left to right, today's bar is at the right`() {
        todayAcross(rtl = false) shouldBeGreaterThan 0.5f
    }

    @Test
    fun `right to left, today's bar is at the left`() {
        todayAcross(rtl = true) shouldBeLessThan 0.5f
    }
}
