package com.calypsan.listenup.client.features.seriesedit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.presentation.merge.MergeHistoryState
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditForm
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A wrongly picked cover can be dropped without discarding the whole draft. The control offers
 * only what [SeriesEditUiEvent.CoverRemoved] does — drop the staged pick — so it appears only
 * while there is one.
 */
@RunWith(RobolectricTestRunner::class)
class SeriesEditCoverPickTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<SeriesEditUiEvent>()

    @Test
    fun `a picked cover can be dropped`() {
        render(
            SAVED.copy(
                stagingCoverPath = "/tmp/staged-cover.jpg",
                pendingCoverData = byteArrayOf(1, 2, 3),
                pendingCoverFilename = "cover.jpg",
            ),
        )

        composeRule.onNodeWithText(KEEP_CURRENT).performScrollTo().performClick()

        events shouldBe listOf(SeriesEditUiEvent.CoverRemoved)
    }

    @Test
    fun `with no pick there is nothing to drop`() {
        render(SAVED)

        composeRule.onNodeWithText(KEEP_CURRENT).assertDoesNotExist()
    }

    private fun render(form: SeriesEditForm) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSnackbarHostState provides SnackbarHostState()) {
                    SeriesEditContent(
                        studio = SeriesEditUiState.Editing(form = form, original = SAVED),
                        mergeHistory = MergeHistoryState.Loading,
                        onEvent = { events += it },
                        onAddSubSeries = {},
                        onMergeClick = {},
                        onBackClick = {},
                    )
                }
            }
        }
    }

    private companion object {
        const val KEEP_CURRENT = "Keep the current cover"
        val SAVED = SeriesEditForm(seriesId = "series-1", name = "The Stormlight Archive", coverPath = "/tmp/cover.jpg")
    }
}
