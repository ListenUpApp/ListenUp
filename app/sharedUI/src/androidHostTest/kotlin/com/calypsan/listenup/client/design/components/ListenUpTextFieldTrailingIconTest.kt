package com.calypsan.listenup.client.design.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The trailing affordance of [ListenUpTextField] is a real button — reveal password, clear search —
 * but it used to render its icon with `contentDescription = null`, so a screen-reader user reached
 * an unlabelled button on the login, sign-up, forgot-password and edit-profile screens with no way
 * to know what it does.
 *
 * These tests pin the label onto the INTERACTIVE branch only: a non-clickable trailing icon is
 * decorative and must stay unlabelled, or the reader announces a decoration as content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ListenUpTextFieldTrailingIconTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a clickable trailing icon exposes its content description`() {
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    value = "",
                    onValueChange = {},
                    label = "Password",
                    trailingIcon = Icons.Default.Search,
                    onTrailingClick = {},
                    trailingIconContentDescription = SHOW_PASSWORD,
                )
            }
        }

        composeRule.onNodeWithContentDescription(SHOW_PASSWORD).assertExists()
    }

    @Test
    fun `clicking the labelled trailing icon invokes onTrailingClick exactly once`() {
        var clicks = 0
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    value = "",
                    onValueChange = {},
                    label = "Password",
                    trailingIcon = Icons.Default.Search,
                    onTrailingClick = { clicks++ },
                    trailingIconContentDescription = SHOW_PASSWORD,
                )
            }
        }

        composeRule.onNodeWithContentDescription(SHOW_PASSWORD).performClick()
        clicks shouldBe 1
    }

    @Test
    fun `a decorative trailing icon stays unlabelled`() {
        // No onTrailingClick: the icon is not a button, so labelling it would announce a decoration.
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    value = "",
                    onValueChange = {},
                    label = "Password",
                    trailingIcon = Icons.Default.Search,
                    trailingIconContentDescription = SHOW_PASSWORD,
                )
            }
        }

        composeRule.onNodeWithContentDescription(SHOW_PASSWORD).assertDoesNotExist()
    }

    @Test
    fun `trailingContent still wins over a labelled trailing icon`() {
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    value = "",
                    onValueChange = {},
                    label = "Search",
                    trailingIcon = Icons.Default.Search,
                    onTrailingClick = {},
                    trailingIconContentDescription = SHOW_PASSWORD,
                    trailingContent = { Text("custom", modifier = Modifier.testTag(TRAILING_TAG)) },
                )
            }
        }

        composeRule.onNodeWithTag(TRAILING_TAG, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription(SHOW_PASSWORD).assertDoesNotExist()
    }
}

private const val SHOW_PASSWORD = "Show password"

private const val TRAILING_TAG = "trailing-icon-test-trailing"
