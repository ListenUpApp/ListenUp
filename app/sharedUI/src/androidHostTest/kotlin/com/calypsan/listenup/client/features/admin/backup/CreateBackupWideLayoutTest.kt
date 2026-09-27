package com.calypsan.listenup.client.features.admin.backup

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertRightOf
import com.calypsan.listenup.client.testing.assertSideBySide
import com.calypsan.listenup.client.testing.assertStacked
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Creating a backup on a tablet sets what goes into it beside a summary panel that ends in the create
 * action; on a phone the options, the summary and the action stack in one column.
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
        assertRightOf(composeRule.onNodeWithText("Create Backup"), composeRule.onNodeWithText("What to include"))
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the options, summary and action stack`() {
        setContent()

        assertStacked(composeRule.onNodeWithText(INTRO), composeRule.onNodeWithText("Create Backup"))
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
