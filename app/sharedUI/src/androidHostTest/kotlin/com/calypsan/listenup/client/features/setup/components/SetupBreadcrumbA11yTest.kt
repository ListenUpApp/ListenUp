package com.calypsan.listenup.client.features.setup.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.testing.assertTouchTargetAtLeast
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The folder browser's breadcrumb is how an admin climbs back out of a deep folder. TalkBack hears
 * the root chip as "Root folder" (not "/") and every crumb as a button with a full target.
 */
@RunWith(RobolectricTestRunner::class)
class SetupBreadcrumbA11yTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the root chip is a named button`() {
        setContent()

        composeRule
            .onNodeWithContentDescription("Root folder")
            .assert(hasButtonRole)
            .assertTouchTargetAtLeast()
    }

    @Test
    fun `a path segment is a button`() {
        setContent()

        composeRule
            .onNodeWithText("audiobooks")
            .assert(hasButtonRole)
            .assertTouchTargetAtLeast()
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                SetupBreadcrumb(path = "/mnt/audiobooks", onNavigate = {})
            }
        }
    }

    private companion object {
        val hasButtonRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    }
}
