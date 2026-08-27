package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.BookSeries
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The series chip's "Book N" position, at the last hop before a person reads it.
 *
 * A position is stored as a number so that a 1.5 interquel can exist at all — but a whole number
 * printed as a `Double` is `"1.0"`, and nobody writes "Book 1.0". `formatSeriesSequence` has always
 * existed for exactly this; the chip simply stopped calling it when the column went from text to a
 * number, and no test looked at the rendered string, so "Book 1.0" shipped.
 *
 * These assert the rendered text, not the formatter — the formatter's own behaviour is covered by
 * `SeriesSequenceTest`. What was missing was anything checking that this screen uses it.
 */
@RunWith(RobolectricTestRunner::class)
class SeriesChipSequenceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun renderSeries(vararg series: BookSeries) {
        composeRule.setContent {
            MaterialTheme {
                SeriesChips(
                    series = series.toList(),
                    onSeriesClick = {},
                    contentColor = Color.Black,
                    centered = false,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a whole-numbered position drops its decimal tail`() {
        renderSeries(BookSeries(seriesId = "s1", seriesName = "Mistborn", sequence = 1.0))

        composeRule.onNodeWithText("Book 1").assertExists()
        composeRule.onNodeWithText("Book 1.0").assertDoesNotExist()
    }

    @Test
    fun `a fractional position keeps it — that is why the column is a number`() {
        renderSeries(BookSeries(seriesId = "s1", seriesName = "Mistborn", sequence = 1.5))

        composeRule.onNodeWithText("Book 1.5").assertExists()
    }

    @Test
    fun `a two-digit position is not truncated`() {
        renderSeries(BookSeries(seriesId = "s1", seriesName = "Discworld", sequence = 10.0))

        composeRule.onNodeWithText("Book 10").assertExists()
    }

    @Test
    fun `an unnumbered membership shows the series alone, with no position`() {
        renderSeries(BookSeries(seriesId = "s1", seriesName = "The Cosmere", sequence = null))

        composeRule.onNodeWithText("The Cosmere").assertExists()
        composeRule.onNodeWithText("Book 0").assertDoesNotExist()
    }

    @Test
    fun `each membership of a multi-series book carries its own position`() {
        renderSeries(
            BookSeries(seriesId = "s1", seriesName = "Mistborn", sequence = 1.0),
            BookSeries(seriesId = "s2", seriesName = "The Cosmere", sequence = 3.5),
        )

        composeRule.onNodeWithText("Book 1").assertExists()
        composeRule.onNodeWithText("Book 3.5").assertExists()
    }
}
