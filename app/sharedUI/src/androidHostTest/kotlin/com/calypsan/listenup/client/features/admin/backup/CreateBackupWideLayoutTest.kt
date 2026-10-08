package com.calypsan.listenup.client.features.admin.backup

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertRightOf
import com.calypsan.listenup.client.testing.assertSideBySide
import com.calypsan.listenup.client.testing.assertStacked
import androidx.compose.ui.unit.width
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Creating a backup on a tablet sets what goes into it beside a summary panel that ends in the create
 * action. A small tablet splits the width evenly between the two, so neither is starved; on a phone
 * the options, the summary and the action stack in one column.
 */
@RunWith(RobolectricTestRunner::class)
class CreateBackupWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the options sit beside the summary and its action`() {
        setContent()

        assertSideBySide(composeRule.onNodeWithText(INTRO), composeRule.onNodeWithText(SUMMARY))
        assertRightOf(composeRule.onNodeWithText("Create backup"), composeRule.onNodeWithText("What to include"))
    }

    @Test
    @Config(qualifiers = Windows.SMALL_TABLET)
    fun `on a small tablet the options and the summary share the width evenly`() {
        setContent()

        assertSideBySide(composeRule.onNodeWithText(INTRO), composeRule.onNodeWithText(SUMMARY))
        assertRightOf(composeRule.onNodeWithText("Create backup"), composeRule.onNodeWithText("What to include"))
        // The action fills the summary's column. A fixed side panel would leave the options column
        // narrower than that; here the options get at least as much of the width.
        val intro = composeRule.onNodeWithText(INTRO).getUnclippedBoundsInRoot()
        val create = composeRule.onNodeWithText("Create backup").getUnclippedBoundsInRoot()
        val optionsColumn = create.left - intro.left
        optionsColumn shouldBeGreaterThanOrEqualTo create.width
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the options, summary and action stack`() {
        setContent()

        assertStacked(composeRule.onNodeWithText(INTRO), composeRule.onNodeWithText("Create backup"))
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                CreateBackupForm(includeImages = false, onIncludeImagesChange = {}, error = null, onCreateClick = {})
            }
        }
    }

    private companion object {
        const val INTRO = "Create a backup of your ListenUp server data."
        const val SUMMARY = "Backup will not include images. Estimated size depends on your library."
    }
}
