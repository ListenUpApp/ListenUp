package com.calypsan.listenup.client.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.LocalInDetailPane
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertSideBySide
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The list-detail scene on the real NavDisplay: a book opened from a series sits beside it on a
 * tablet and replaces it on a phone, and each pane lays itself out for its own width.
 */
@RunWith(RobolectricTestRunner::class)
class ListDetailSceneTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the book opens beside its series`() {
        setContent()

        assertSideBySide(composeRule.onNodeWithText(LIST), composeRule.onNodeWithText(DETAIL))
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the book replaces its series`() {
        setContent()

        composeRule.onNodeWithText(DETAIL).assertIsDisplayed()
        composeRule.onNodeWithText(LIST).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `each pane picks its layout from its own width, not the window's`() {
        setContent()

        // 1280dp is an expanded window; neither a 40% nor a 60% pane of it is.
        composeRule.onNodeWithText("$LIST sees expanded: false").assertIsDisplayed()
        composeRule.onNodeWithText("$DETAIL sees expanded: false").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `only the detail pane is told it sits beside a list`() {
        setContent()

        composeRule.onNodeWithText("$LIST in detail pane: false").assertIsDisplayed()
        composeRule.onNodeWithText("$DETAIL in detail pane: true").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `a book alone on a phone keeps its back arrow`() {
        setContent()

        composeRule.onNodeWithText("$DETAIL in detail pane: false").assertIsDisplayed()
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                val backStack = rememberNavBackStack(Shell, SeriesDetail("s1"), BookDetail("b1"))
                NavDisplay(
                    backStack = backStack,
                    entryDecorators =
                        listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                    sceneStrategies = listOf(rememberListDetailSceneStrategy()),
                    entryProvider =
                        entryProvider<NavKey> {
                            entry<Shell> { Text("Shell") }
                            entry<SeriesDetail>(metadata = ListDetailScene.listPane()) { Probe(LIST) }
                            entry<BookDetail>(metadata = ListDetailScene.detailPane()) { Probe(DETAIL) }
                        },
                )
            }
        }
    }

    private companion object {
        const val LIST = "Series"
        const val DETAIL = "Book"
    }
}

/** Names the pane, then reports what the pane tells it about its width and its role. */
@Composable
private fun Probe(name: String) {
    val expanded =
        currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )
    Column {
        Text(name)
        Text("$name sees expanded: $expanded")
        Text("$name in detail pane: ${LocalInDetailPane.current}")
    }
}
