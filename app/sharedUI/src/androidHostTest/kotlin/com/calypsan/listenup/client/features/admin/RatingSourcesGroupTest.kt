package com.calypsan.listenup.client.features.admin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.dto.admin.RatingSourceStatus
import com.calypsan.listenup.api.sync.ExternalRatingSource
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The admin "Rating sources" section: one row per outside catalog, its switch, and its health
 * line — the surface [com.calypsan.listenup.client.presentation.admin.AdminSettingsViewModel.setRatingSourceEnabled]
 * is wired to.
 */
@RunWith(RobolectricTestRunner::class)
class RatingSourcesGroupTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `lists every source with its health line`() {
        composeRule.setContent {
            MaterialTheme {
                RatingSourcesGroup(
                    sources =
                        listOf(
                            RatingSourceStatus(
                                source = ExternalRatingSource.AUDIBLE,
                                enabled = true,
                                lastFetchedAt = null,
                                lastError = null,
                            ),
                            RatingSourceStatus(
                                source = ExternalRatingSource.HARDCOVER,
                                enabled = false,
                                lastFetchedAt = null,
                                lastError = "Timed out",
                            ),
                        ),
                    onSourceEnabledChange = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Audible").assertIsDisplayed()
        composeRule.onNodeWithText("Not fetched yet").assertIsDisplayed()
        composeRule.onNodeWithText("Hardcover").assertIsDisplayed()
        composeRule.onNodeWithText("Last attempt failed: Timed out").assertIsDisplayed()
    }

    @Test
    fun `a source's switch reflects its enabled flag`() {
        composeRule.setContent {
            MaterialTheme {
                RatingSourcesGroup(
                    sources =
                        listOf(
                            RatingSourceStatus(
                                source = ExternalRatingSource.AUDIBLE,
                                enabled = true,
                                lastFetchedAt = null,
                                lastError = null,
                            ),
                        ),
                    onSourceEnabledChange = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("ratingSourceSwitch_AUDIBLE").assertIsOn()
    }

    @Test
    fun `toggling a source's switch calls setRatingSourceEnabled with its source`() {
        var toggledSource: ExternalRatingSource? = null
        var toggledEnabled: Boolean? = null
        composeRule.setContent {
            MaterialTheme {
                RatingSourcesGroup(
                    sources =
                        listOf(
                            RatingSourceStatus(
                                source = ExternalRatingSource.AUDIBLE,
                                enabled = true,
                                lastFetchedAt = null,
                                lastError = null,
                            ),
                        ),
                    onSourceEnabledChange = { source, enabled ->
                        toggledSource = source
                        toggledEnabled = enabled
                    },
                )
            }
        }

        composeRule.onNodeWithTag("ratingSourceSwitch_AUDIBLE").performClick()

        toggledSource shouldBe ExternalRatingSource.AUDIBLE
        toggledEnabled shouldBe false
    }
}
