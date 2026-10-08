package com.calypsan.listenup.client.features.seriesdetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.client.testing.Windows
import kotlin.time.Duration.Companion.hours
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Edit series belongs to Edit metadata: a reader who may edit sees the hero's pencil on the phone and
 * the wide layout alike, and one who may not sees it on neither.
 */
@RunWith(RobolectricTestRunner::class)
class SeriesDetailEditPermissionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun showNarrow(canEditMetadata: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                NarrowSeriesDetailContent(
                    state = ready(canEditMetadata),
                    onBackClick = {},
                    onBookClick = {},
                    onContributorClick = {},
                    onShowAuthors = {},
                    onEditClick = {},
                )
            }
        }
    }

    private fun showWide(canEditMetadata: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                WideSeriesDetailContent(
                    state = ready(canEditMetadata),
                    onBackClick = {},
                    onBookClick = {},
                    onContributorClick = {},
                    onShowAuthors = {},
                    onEditClick = {},
                )
            }
        }
    }

    @Test
    fun `a reader who may edit metadata gets Edit series on the phone`() {
        showNarrow(canEditMetadata = true)
        composeRule.onNodeWithContentDescription(EDIT_SERIES).assertIsDisplayed()
    }

    @Test
    fun `a reader who may not edit metadata gets no Edit series on the phone`() {
        showNarrow(canEditMetadata = false)
        composeRule.onNodeWithContentDescription(EDIT_SERIES).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `a reader who may edit metadata gets Edit series in the wide layout`() {
        showWide(canEditMetadata = true)
        composeRule.onNodeWithContentDescription(EDIT_SERIES).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `a reader who may not edit metadata gets no Edit series in the wide layout`() {
        showWide(canEditMetadata = false)
        composeRule.onNodeWithContentDescription(EDIT_SERIES).assertDoesNotExist()
    }

    private companion object {
        const val EDIT_SERIES = "Edit series"

        fun ready(canEditMetadata: Boolean) =
            SeriesDetailUiState.Ready(
                seriesId = "s1",
                seriesName = "Mistborn",
                seriesDescription = null,
                seriesAuthors = emptyList(),
                seriesNarrator = null,
                coverPath = null,
                featuredBookId = null,
                totalDuration = 2.hours,
                books = emptyList(),
                bookProgress = emptyMap(),
                finishedBookIds = emptySet(),
                resumeTarget = null,
                canEditMetadata = canEditMetadata,
            )
    }
}
