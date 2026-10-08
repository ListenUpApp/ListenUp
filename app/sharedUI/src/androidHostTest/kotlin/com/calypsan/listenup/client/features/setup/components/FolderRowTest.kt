package com.calypsan.listenup.client.features.setup.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.DirectoryEntry
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the library-setup folder checkbox for TalkBack. For a folder with subfolders a tap on the
 * row OPENS it, so this checkbox is the only way to include the folder — before this it had no
 * name, no role, no state and a ~30dp target, and a screen-reader admin could not set up a library.
 */
@RunWith(RobolectricTestRunner::class)
class FolderRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the include checkbox is named for its folder`() {
        composeRule.setContent {
            MaterialTheme {
                FolderRow(entry = ENTRY, selected = false, onToggle = {})
            }
        }

        composeRule
            .onNodeWithContentDescription("Include Audiobooks")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertIsOff()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `a selected folder reports checked`() {
        composeRule.setContent {
            MaterialTheme {
                FolderRow(entry = ENTRY, selected = true, onToggle = {})
            }
        }

        composeRule.onNodeWithContentDescription("Include Audiobooks").assertIsOn()
    }

    @Test
    fun `activating the checkbox includes the folder`() {
        var toggles = 0
        composeRule.setContent {
            MaterialTheme {
                FolderRow(entry = ENTRY, selected = false, onToggle = { toggles++ })
            }
        }

        composeRule.onNodeWithContentDescription("Include Audiobooks").performClick()

        toggles shouldBe 1
    }

    private companion object {
        val ENTRY = DirectoryEntry(name = "Audiobooks", path = "/media/Audiobooks", hasChildren = true, itemCount = 12)
    }
}
