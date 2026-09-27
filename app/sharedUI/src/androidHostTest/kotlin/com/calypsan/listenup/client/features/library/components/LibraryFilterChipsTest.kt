package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.features.library.LibraryFilter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the Library filter row's selection semantics: the active filter is announced as the
 * selected tab, so TalkBack can say which view of the library is showing — not only its colour.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryFilterChipsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the active filter is the selected tab`() {
        composeRule.setContent {
            MaterialTheme {
                LibraryFilterChips(selected = LibraryFilter.Series, onSelect = {})
            }
        }

        composeRule
            .onNodeWithText(LibraryFilter.Series.label)
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        composeRule.onNodeWithText(LibraryFilter.Books.label).assertIsNotSelected()
    }
}
