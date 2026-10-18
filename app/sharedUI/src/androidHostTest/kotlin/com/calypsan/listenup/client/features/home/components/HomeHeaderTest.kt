package com.calypsan.listenup.client.features.home.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.home.TimeOfDay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** H2: the greeting is worded here, from the catalog, never by the ViewModel. */
@RunWith(RobolectricTestRunner::class)
class HomeHeaderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `an evening with a name reads as two lines`() {
        composeRule.setContent { MaterialTheme { HomeHeader(TimeOfDay.Evening, firstName = "Simon", isWide = false) } }
        composeRule.onNodeWithText("Good evening,").assertIsDisplayed()
        composeRule.onNodeWithText("Simon").assertIsDisplayed()
    }

    @Test
    fun `no name leaves the greeting alone, without a dangling comma`() {
        composeRule.setContent { MaterialTheme { HomeHeader(TimeOfDay.Morning, firstName = null, isWide = true) } }
        composeRule.onNodeWithText("Good morning").assertIsDisplayed()
    }

    @Test
    fun `a new listener is welcomed by name`() {
        composeRule.setContent {
            MaterialTheme { HomeHeader(TimeOfDay.Evening, firstName = "Simon", isWide = false, isWelcome = true) }
        }
        composeRule.onNodeWithText("Welcome,").assertIsDisplayed()
        composeRule.onNodeWithText("Simon").assertIsDisplayed()
    }

    @Test
    fun `a new listener without a name is simply welcomed`() {
        composeRule.setContent { MaterialTheme { HomeHeader(TimeOfDay.Night, firstName = null, isWide = false, isWelcome = true) } }
        composeRule.onNodeWithText("Welcome").assertIsDisplayed()
    }
}
