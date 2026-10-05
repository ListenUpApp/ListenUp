package com.calypsan.listenup.client.features.admin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Admin → Store region: the library's Audible store, chosen from a menu and saved on choice. */
@RunWith(RobolectricTestRunner::class)
class StoreRegionGroupTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `shows the library's store and what the setting does`() {
        composeRule.setContent {
            MaterialTheme { StoreRegionGroup(region = "uk", onRegionChange = {}) }
        }

        composeRule.onNodeWithText("United Kingdom").assertIsDisplayed()
        composeRule
            .onNodeWithText("Used for Audible searches in this library. Each search can still pick another store.")
            .assertIsDisplayed()
    }

    @Test
    fun `choosing another store saves it`() {
        var chosen: String? = null
        composeRule.setContent {
            MaterialTheme { StoreRegionGroup(region = "uk", onRegionChange = { chosen = it }) }
        }

        composeRule.onNodeWithTag(STORE_REGION_FIELD_TAG).performClick()
        composeRule.onNodeWithText("Australia").performClick()

        chosen shouldBe "au"
    }
}
