package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.presentation.bookdetail.BookSeriesPath
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesCrumb
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Book Detail's series path — "Cosmere › Mistborn › Mistborn Era 1 #1" — which replaced the series
 * chips.
 *
 * It keeps the chips' regression guard: a position is stored as a number so a 1.5 interquel can
 * exist, but a whole number printed as a `Double` is `"1.0"`, and "Book 1.0" once shipped because no
 * test looked at the rendered string. These assert the rendered text, built the way the ViewModel
 * builds it (from `BookSeries.sequenceLabel`), plus the links and the fold.
 */
@RunWith(RobolectricTestRunner::class)
class SeriesPathLinesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun path(
        membership: BookSeries,
        vararg ancestors: SeriesCrumb,
    ) = BookSeriesPath(membership.seriesId, membership.seriesName, membership.sequenceLabel, ancestors.toList())

    private fun render(vararg paths: BookSeriesPath) {
        composeRule.setContent {
            MaterialTheme {
                SeriesPathLines(
                    paths = paths.toList(),
                    onSeriesClick = { opened += it },
                    contentColor = Color.Black,
                    centered = false,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a whole-numbered position drops its decimal tail`() {
        render(path(BookSeries("s1", "Mistborn", 1.0)))

        composeRule.onNodeWithText("Mistborn #1").assertExists()
        composeRule.onNodeWithText("Mistborn #1.0").assertDoesNotExist()
    }

    @Test
    fun `a fractional position keeps it — that is why the column is a number`() {
        render(path(BookSeries("s1", "Mistborn", 1.5)))

        composeRule.onNodeWithText("Mistborn #1.5").assertExists()
    }

    @Test
    fun `an unnumbered membership shows the series alone`() {
        render(path(BookSeries("s1", "The Cosmere", null)))

        composeRule.onNodeWithText("The Cosmere").assertExists()
    }

    @Test
    fun `every name in the path opens its series`() {
        render(path(BookSeries("era1", "Mistborn Era 1", 1.0), SeriesCrumb("cosmere", "Cosmere"), SeriesCrumb("mistborn", "Mistborn")))

        composeRule.onNodeWithContentDescription("Series path").assertExists()
        composeRule.onNodeWithText("Cosmere").performClick()
        composeRule.onNodeWithText("Mistborn").performClick()
        composeRule.onNodeWithText("Mistborn Era 1 #1").performClick()

        opened shouldBe listOf("cosmere", "mistborn", "era1")
    }

    @Test
    fun `each series of a multi-series book gets its own line`() {
        render(path(BookSeries("s1", "Mistborn", 1.0)), path(BookSeries("s2", "Secret Projects", 3.5)))

        composeRule.onNodeWithText("Mistborn #1").assertExists()
        composeRule.onNodeWithText("Secret Projects #3.5").assertExists()
    }

    @Test
    fun `four levels fold the middle, and the fold expands in place`() {
        render(
            path(
                BookSeries("era1", "Mistborn Era 1", 1.0),
                SeriesCrumb("cosmere", "Cosmere"),
                SeriesCrumb("scadrial", "Scadrial"),
                SeriesCrumb("mistborn", "Mistborn"),
            ),
        )

        composeRule.onNodeWithText("Scadrial").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Show the full series path").performClick()
        composeRule.onNodeWithText("Scadrial").assertExists()
    }
}
