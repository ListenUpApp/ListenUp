package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.features.library.LibrarySection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibrarySectionGroupTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the selected section is on and In progress is no longer a section`() {
        composeRule.setContent { MaterialTheme { LibrarySectionGroup(selected = LibrarySection.Series, onSelect = {}) } }

        composeRule.onNodeWithText("Series").assertIsOn()
        listOf("Books", "Authors", "Narrators").forEach { composeRule.onNodeWithText(it).assertIsOff() }
        composeRule.onAllNodes(hasText("In progress")).assertCountEquals(0)
    }

    @Test
    fun `choosing a segment reports it`() {
        var chosen: LibrarySection? = null
        composeRule.setContent { MaterialTheme { LibrarySectionGroup(selected = LibrarySection.Books, onSelect = { chosen = it }) } }

        composeRule.onNodeWithText("Authors").performClick()

        assertEquals(LibrarySection.Authors, chosen)
    }
}
