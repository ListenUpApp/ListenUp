package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The lock shows exactly for the ids the shell publishes, and nothing outside a provider. */
@RunWith(RobolectricTestRunner::class)
class RestrictedBookMarkerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a restricted book carries the lock`() {
        setContent(restricted = setOf("b1"), bookId = "b1")
        composeRule.onNodeWithContentDescription(RESTRICTED_A11Y, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `any other book carries none`() {
        setContent(restricted = setOf("b2"), bookId = "b1")
        composeRule.onNodeWithContentDescription(RESTRICTED_A11Y, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `with no provider — a member's device, or the frozen desktop — there is no lock`() {
        composeRule.setContent { MaterialTheme { RestrictedBookMarker(bookId = "b1") } }
        composeRule.onNodeWithContentDescription(RESTRICTED_A11Y, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun setContent(
        restricted: Set<String>,
        bookId: String,
    ) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalRestrictedBookIds provides restricted) {
                    RestrictedBookMarker(bookId = bookId)
                }
            }
        }
    }

    private companion object {
        const val RESTRICTED_A11Y = "In a collection, so only people it is shared with can see it."
    }
}
