package com.calypsan.listenup.client.features.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.SearchHitType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The search overlay's expansion survives rotation; the See-all page open inside it must too, rather
 * than dropping back to the grouped list.
 */
@RunWith(RobolectricTestRunner::class)
class SearchSeeAllRotationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `an open See-all page survives rotation`() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            var seeAllType by rememberSeeAllType()
            TextButton(onClick = { seeAllType = SearchHitType.SERIES }) { Text("See all series") }
            Text("Open: ${seeAllType?.name ?: "none"}")
        }
        composeRule.onNodeWithText("See all series").performClick()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Open: SERIES").assertExists()
    }

    @Test
    fun `no See-all page stays none across rotation`() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            val seeAllType by rememberSeeAllType()
            Text("Open: ${seeAllType?.name ?: "none"}")
        }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Open: none").assertExists()
    }
}
