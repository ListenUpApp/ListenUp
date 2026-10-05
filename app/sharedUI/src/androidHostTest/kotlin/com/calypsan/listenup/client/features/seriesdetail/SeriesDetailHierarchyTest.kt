package com.calypsan.listenup.client.features.seriesdetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The series page's hierarchy: the breadcrumb that replaces the overline on a child, the Sub-series
 * cards, the grouped book list with its foldable headings, and the Continue button that names the
 * book on a parent page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TALL_PHONE)
class SeriesDetailHierarchyTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String>()
    private val toggled = mutableListOf<String>()
    private var addClicks = 0

    private fun render(
        state: SeriesDetailUiState.Ready,
        wide: Boolean = false,
    ) {
        val hierarchy =
            SeriesPageHierarchyActions(
                onSeriesClick = { opened += it },
                onToggleSection = { toggled += it },
                onAddSubSeries = { addClicks++ },
            )
        composeRule.setContent {
            MaterialTheme {
                if (wide) {
                    WideSeriesDetailContent(state, {}, {}, {}, {}, {}, hierarchy)
                } else {
                    NarrowSeriesDetailContent(state, {}, {}, {}, {}, {}, hierarchy)
                }
            }
        }
    }

    @Test
    fun `a child page shows its breadcrumb instead of the overline, and a crumb opens that series`() {
        render(Cosmere.childPage)

        composeRule.onNodeWithText("SERIES").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Part of Cosmere, Mistborn").assertExists()
        composeRule.onNodeWithText("Cosmere").assertHasClickAction().performClick()

        opened shouldBe listOf("cosmere")
    }

    @Test
    fun `a top-level page keeps the overline`() {
        render(Cosmere.parentPage())

        composeRule.onNodeWithText("SERIES").assertExists()
    }

    @Test
    fun `a sub-series card reads as one node and opens its series`() {
        render(Cosmere.parentPage())

        composeRule
            .onNodeWithContentDescription("Mistborn, 2 series, 8 books, 2 finished")
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithContentDescription("Elantris, 1 book, Finished").assertExists()

        opened shouldBe listOf("mistborn")
    }

    @Test
    fun `the parent hero counts series and books`() {
        render(Cosmere.parentPage())

        composeRule.onNodeWithText("2 series · 4 books").assertExists()
    }

    @Test
    fun `an editor gets the add tile, disabled offline, and a reader does not`() {
        render(Cosmere.parentPage(canEdit = true, online = false))

        composeRule.onNode(hasClickAction() and hasText("Add sub-series", substring = true)).assertIsNotEnabled()
    }

    @Test
    fun `the add tile opens the sheet when online`() {
        render(Cosmere.parentPage(canEdit = true))

        composeRule.onNode(hasClickAction() and hasText("Add sub-series")).assertIsEnabled().performClick()

        addClicks shouldBe 1
    }

    @Test
    fun `a reader sees no add tile`() {
        render(Cosmere.parentPage())

        composeRule.onNodeWithText("Add sub-series").assertDoesNotExist()
    }

    @Test
    fun `group headings are headings and open their series`() {
        render(Cosmere.parentPage())

        composeRule.onNodeWithText("Books").assert(isHeading)
        composeRule.onNodeWithText("Mistborn Era 1").assert(isHeading).performClick()
        composeRule.onNodeWithText("Also in Cosmere").assert(isHeading)

        opened shouldBe listOf("era1")
    }

    @Test
    fun `a grouped book is numbered in its own series`() {
        render(Cosmere.parentPage())

        composeRule.onNodeWithText("Book 3").assertExists()
    }

    @Test
    fun `a folded group offers Show all, which unfolds it`() {
        render(Cosmere.parentPage())

        composeRule.onNodeWithText("Show all 4").performClick()

        toggled shouldBe listOf("elantris")
    }

    @Test
    fun `Continue on a parent page names the book and where it sits`() {
        render(Cosmere.parentPage())

        composeRule.onNodeWithText("Continue The Hero of Ages").assertExists()
        composeRule.onNodeWithText("Mistborn Era 1 · Book 3").assertExists()
    }

    @Test
    fun `an unstarted parent page's button starts the book it names, with the series underneath`() {
        render(Cosmere.unstartedParentPage())

        composeRule.onNodeWithText("Start The Final Empire").assertExists()
        composeRule.onNodeWithText("Mistborn Era 1 · Book 1").assertExists()
        composeRule.onNodeWithText("Continue The Final Empire").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = SHORT_PHONE)
    fun `a group heading sticks below the status bar while its books scroll under it`() {
        composeRule.setContent {
            MaterialTheme {
                NarrowSeriesDetailContent(
                    Cosmere.longParentPage(),
                    {},
                    {},
                    {},
                    {},
                    {},
                    stickyTopInset = STATUS_BAR,
                )
            }
        }

        // Deep into Mistborn Era 1: the heading's own row is long gone above the viewport.
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(ERA_ONE_BOOK_20)

        val heading = composeRule.onNodeWithText("Mistborn Era 1")
        heading.assertIsDisplayed().assert(isHeading)
        val top = heading.getUnclippedBoundsInRoot().top
        (top >= STATUS_BAR) shouldBe true
        (top < STATUS_BAR + 48.dp) shouldBe true
    }

    // The verb is the ViewModel's (resumeBook.hasStarted), the same on every platform: a series with
    // a finished book is under way even when the next book has no progress of its own.
    @Test
    fun `a flat page that is under way continues the next book`() {
        render(Cosmere.childPageUnderWay)

        composeRule.onNodeWithText("Continue Book 3").assertExists()
        composeRule.onNodeWithText("Start Book 3").assertDoesNotExist()
    }

    @Test
    fun `a flat page keeps Books in series`() {
        render(Cosmere.childPage)

        composeRule.onNodeWithText("Books in series").assertExists()
        composeRule.onNodeWithText("Also in Mistborn Era 1").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = TALL_TABLET)
    fun `the wide layout carries the sub-series and the groups too`() {
        render(Cosmere.parentPage(), wide = true)

        composeRule.onNodeWithContentDescription("Mistborn, 2 series, 8 books, 2 finished").assertExists()
        composeRule.onNodeWithText("Also in Cosmere").assertExists()
    }

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
}

private const val TALL_PHONE = "w400dp-h4000dp"
private const val SHORT_PHONE = "w400dp-h800dp"
private val STATUS_BAR = 40.dp

// hero, Continue, Sub-series, Books, the Mistborn heading, the Era 1 heading, then Era 1's books.
private const val ERA_ONE_BOOK_20 = 6 + 19
private const val TALL_TABLET = "w1280dp-h4000dp"
