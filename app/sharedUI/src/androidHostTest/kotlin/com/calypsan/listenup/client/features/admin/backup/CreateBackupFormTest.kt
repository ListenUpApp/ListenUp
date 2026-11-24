package com.calypsan.listenup.client.features.admin.backup

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Create stays disabled until the backup list has loaded: before then the ViewModel ignores the
 * request, so an enabled button would do nothing.
 */
@RunWith(RobolectricTestRunner::class)
class CreateBackupFormTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun createIsDisabledUntilTheBackupsHaveLoaded() {
        setForm(createEnabled = false)

        composeRule.onNodeWithText("Create backup").assertIsNotEnabled()
    }

    @Test
    fun createIsEnabledOnceTheBackupsHaveLoaded() {
        setForm(createEnabled = true)

        composeRule.onNodeWithText("Create backup").assertIsEnabled()
    }

    private fun setForm(createEnabled: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                CreateBackupForm(
                    includeImages = false,
                    onIncludeImagesChange = {},
                    error = null,
                    createEnabled = createEnabled,
                    onCreateClick = {},
                )
            }
        }
    }
}
