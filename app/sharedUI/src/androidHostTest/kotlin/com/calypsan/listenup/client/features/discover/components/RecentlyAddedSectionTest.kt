package com.calypsan.listenup.client.features.discover.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.features.discover.DAY_MS
import com.calypsan.listenup.client.features.discover.NOW_MS
import com.calypsan.listenup.client.features.discover.WithAvatarDependencies
import com.calypsan.listenup.client.features.discover.recent
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiBook
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Recently added says when each book arrived, and "In Library" opens the Library newest first. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RecentlyAddedSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun show(
        books: List<RecentlyAddedUiBook>,
        onOpenLibraryNewest: () -> Unit = {},
    ) {
        composeRule.setContent {
            WithAvatarDependencies {
                RecentlyAddedSection(
                    state = RecentlyAddedUiState.Ready(books),
                    onBookClick = {},
                    onOpenLibraryNewest = onOpenLibraryNewest,
                    onRetry = {},
                    nowMs = NOW_MS,
                )
            }
        }
    }

    @Test
    fun aBookSaysWhenItWasAdded() {
        show(listOf(recent("r1", "Orbital", createdAt = NOW_MS - 2 * DAY_MS)))
        composeRule.onNodeWithText("Added 2 days ago", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Kaliane Bradley", useUnmergedTree = true).assertExists()
    }

    @Test
    fun inLibraryOpensTheLibraryNewestFirst() {
        var opened = 0
        show(listOf(recent("r1", "Orbital", NOW_MS)), onOpenLibraryNewest = { opened++ })
        composeRule.onNodeWithText("In Library").assertHasClickAction().performClick()
        assertEquals(1, opened)
    }
}
