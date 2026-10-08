package com.calypsan.listenup.client.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings offers Categories to whoever may curate the library, and to nobody else. */
@RunWith(RobolectricTestRunner::class)
class CurationSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a curator gets a Categories row that opens the screen`() {
        var opened = 0
        composeRule.setContent { MaterialTheme { CurationRow(onOpenCategories = { opened++ }) } }
        composeRule.onNodeWithText("Categories").assertIsDisplayed()
        composeRule.onNodeWithText("Merge and delete genres for everyone").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `without Curate library there is no row`() {
        composeRule.setContent { MaterialTheme { CurationRow(onOpenCategories = null) } }
        composeRule.onNodeWithText("Categories").assertDoesNotExist()
    }
}
