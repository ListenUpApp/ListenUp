package com.calypsan.listenup.client.features.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.calypsan.listenup.api.dto.admin.HardcoverApiTokenStatus
import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceUnavailable
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.client.presentation.admin.HardcoverTokenSave
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Admin → Hardcover on Android (#1542): a write-only token, Remove that asks first, and the metadata switch. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp")
class HardcoverSourceGroupTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val saved = mutableListOf<String>()
    private var removed = 0
    private val metadata = mutableListOf<Boolean>()
    private var cleared = 0
    private val actions =
        HardcoverSourceActions(
            onSaveToken = { saved += it },
            onRemoveToken = { removed++ },
            onMetadataEnabledChange = { metadata += it },
            onClearTokenError = { cleared++ },
        )

    private fun show(
        status: HardcoverSourceStatus = HardcoverSourceStatus(),
        save: HardcoverTokenSave = HardcoverTokenSave.Idle,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HardcoverSourceGroup(status = status, tokenSave = save, actions = actions)
                }
            }
        }
    }

    @Test
    fun `with no token, Save stays off until something is typed, then sends it once`() {
        show()

        composeRule.onNodeWithTag("hardcoverTokenSave").assertIsNotEnabled()
        composeRule.onNodeWithTag("hardcoverTokenField").performTextInput("hc_ui_test_token")
        composeRule.onNodeWithTag("hardcoverTokenSave").performClick()

        saved shouldBe listOf("hc_ui_test_token")
    }

    @Test
    fun `while Hardcover checks the token, the section says so and Save can't be pressed again`() {
        show(save = HardcoverTokenSave.Busy)

        composeRule.onNodeWithText("Checking with Hardcover…").assertIsDisplayed()
        composeRule.onNodeWithTag("hardcoverTokenSave").assertIsNotEnabled()
    }

    @Test
    fun `a refused token is explained beside the field, and typing again clears it`() {
        show(save = HardcoverTokenSave.Refused(HardcoverError.TokenRejected()))

        composeRule.onNodeWithText("Hardcover didn't accept that token. Check it and try again.").assertIsDisplayed()
        composeRule.onNodeWithTag("hardcoverTokenField").performTextInput("x")

        cleared shouldBe 1
    }

    @Test
    fun `a saved token shows only whose it is, and Remove asks before it removes`() {
        show(HardcoverSourceStatus(apiToken = HardcoverApiTokenStatus.Saved("simon", 1L)))

        composeRule.onNodeWithText("Set · belongs to @simon").assertIsDisplayed()
        composeRule.onNodeWithTag("hardcoverTokenRemove").performClick()
        composeRule.onNodeWithText("Remove the API token?").assertIsDisplayed()
        removed shouldBe 0
        composeRule.onAllNodesWithText("Remove").onLast().performClick()

        removed shouldBe 1
    }

    @Test
    fun `Replace brings the field back`() {
        show(HardcoverSourceStatus(apiToken = HardcoverApiTokenStatus.Saved("simon", 1L)))

        composeRule.onNodeWithText("Replace").performClick()

        composeRule.onNodeWithTag("hardcoverTokenField").assertIsDisplayed()
    }

    @Test
    fun `a token Hardcover rejected since asks to be replaced`() {
        show(HardcoverSourceStatus(apiToken = HardcoverApiTokenStatus.Rejected("simon")))

        composeRule.onNodeWithText("Hardcover rejected this token — replace it").assertIsDisplayed()
        composeRule.onNodeWithTag("hardcoverTokenField").assertIsDisplayed()
    }

    @Test
    fun `the metadata switch reflects the setting and flips it`() {
        show()

        composeRule.onNodeWithTag("hardcoverMetadataSwitch").assertIsOn()
        composeRule.onNodeWithTag("hardcoverMetadataSwitch").performClick()

        metadata shouldBe listOf(false)
    }

    @Test
    fun `with nothing to read Hardcover with, the switch says how to enable it`() {
        show(HardcoverSourceStatus(metadataUnavailable = RatingSourceUnavailable.NO_CONNECTION))

        composeRule.onNodeWithText("Add an API token or connect a Hardcover account to enable").assertIsDisplayed()
        composeRule.onNodeWithText("Get a token from Hardcover").assertIsDisplayed()
    }
}
