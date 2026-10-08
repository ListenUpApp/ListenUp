package com.calypsan.listenup.client.features.admin.categories

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesUiState
import com.calypsan.listenup.client.presentation.admin.GenreTreeNode
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertSideBySide
import com.calypsan.listenup.client.testing.assertStacked
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Categories on a tablet keep the tree in one column and give the selected category a detail panel
 * beside it, its actions laid out as rows instead of hidden behind a long-press; on a phone the tree
 * is the whole screen, exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
class AdminCategoriesWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the tree sits beside a detail panel`() {
        setContent()

        assertSideBySide(composeRule.onNodeWithText("Fantasy"), composeRule.onNodeWithText(SELECT_HINT))
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet tapping a category shows its actions beside the tree`() {
        setContent()

        composeRule.onNodeWithText("Science Fiction").performClick()

        val treeRow = composeRule.onAllNodesWithText("Science Fiction").onFirst()
        val panelHeading = composeRule.onAllNodesWithText("Science Fiction").onLast()
        treeRow.assertIsSelected()
        panelHeading.assert(isHeading())
        assertSideBySide(composeRule.onNodeWithText("2 categories • 19 books"), panelHeading)
        composeRule.onNodeWithText("Rename").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the tree is one column with no detail panel`() {
        setContent()

        assertStacked(composeRule.onNodeWithText("Fantasy"), composeRule.onNodeWithText("Science Fiction"))
        composeRule.onNodeWithText(SELECT_HINT).assertDoesNotExist()
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                AdminCategoriesReadyContent(
                    state = STATE,
                    onToggleExpanded = {},
                    onAddChild = { _, _ -> },
                    onRename = { _, _ -> },
                    onDelete = { _, _ -> },
                    onMerge = { _, _ -> },
                    onMergeHistory = {},
                    onMove = { _, _ -> },
                    onMoveGenre = { _, _ -> },
                )
            }
        }
    }

    private companion object {
        const val SELECT_HINT = "Select a category to rename, move, merge or delete it."
        val FANTASY = Genre(id = "g1", name = "Fantasy", slug = "fantasy", path = "/fantasy", bookCount = 12)
        val SCIENCE_FICTION =
            Genre(id = "g2", name = "Science Fiction", slug = "science-fiction", path = "/science-fiction", bookCount = 7)
        val STATE =
            AdminCategoriesUiState.Ready(
                genres = listOf(FANTASY, SCIENCE_FICTION),
                tree =
                    listOf(
                        GenreTreeNode(genre = FANTASY, children = emptyList(), depth = 0),
                        GenreTreeNode(genre = SCIENCE_FICTION, children = emptyList(), depth = 0),
                    ),
                totalBookCount = 19,
                canEditMetadata = true,
                canCurateLibrary = true,
            )
    }
}
