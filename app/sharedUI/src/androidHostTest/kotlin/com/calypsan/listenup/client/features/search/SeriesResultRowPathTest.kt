package com.calypsan.listenup.client.features.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A series hit says where the series sits, because the Library grid only shows top-level series:
 * search is how a reader finds Mistborn Era 1, and "in Cosmere › Mistborn" is how they know it.
 */
@RunWith(RobolectricTestRunner::class)
class SeriesResultRowPathTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun render(path: List<String>) {
        composeRule.setContent {
            MaterialTheme {
                SeriesResultRow(
                    hit =
                        SearchHit(
                            id = "era1",
                            type = SearchHitType.SERIES,
                            name = "Mistborn Era 1",
                            bookCount = 4,
                            seriesPath = path,
                        ),
                    query = "",
                    onClick = {},
                )
            }
        }
    }

    @Test
    fun `a sub-series hit names its path and its count`() {
        render(listOf("Cosmere", "Mistborn"))

        composeRule.onNodeWithText("in Cosmere › Mistborn · 4 books", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a top-level hit shows just its count`() {
        render(emptyList())

        composeRule.onNodeWithText("4 books", useUnmergedTree = true).assertExists()
    }
}
