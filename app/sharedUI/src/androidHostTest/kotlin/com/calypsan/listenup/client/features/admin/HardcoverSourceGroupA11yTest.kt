package com.calypsan.listenup.client.features.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.admin.HardcoverApiTokenStatus
import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.client.presentation.admin.HardcoverTokenSave
import com.calypsan.listenup.client.testing.AtFontScale
import com.calypsan.listenup.client.testing.assertNoMidWordBreaks
import com.calypsan.listenup.client.testing.assertNotClipped
import com.calypsan.listenup.client.testing.isLiveRegion
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Admin → Hardcover with TalkBack and at the largest font (#1562). */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class HardcoverSourceGroupA11yTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        status: HardcoverSourceStatus = HardcoverSourceStatus(apiToken = HardcoverApiTokenStatus.Saved("simon", 1L)),
        save: HardcoverTokenSave = HardcoverTokenSave.Idle,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            AtFontScale(fontScale) {
                MaterialTheme {
                    Column(Modifier.width(PHONE_COLUMN).verticalScroll(rememberScrollState())) {
                        HardcoverSourceGroup(status = status, tokenSave = save, actions = HardcoverSourceActions())
                    }
                }
            }
        }
    }

    @Test
    fun `Replace and Remove say what they replace and remove`() {
        show()

        composeRule.onNodeWithContentDescription("Replace Hardcover API token").assertExists()
        composeRule.onNodeWithContentDescription("Remove Hardcover API token").assertExists()
    }

    @Test
    fun `at the largest font Replace and Remove are both whole`() {
        show(fontScale = 2f)
        composeRule.onNodeWithText("Replace", useUnmergedTree = true).assertNotClipped().assertNoMidWordBreaks()
        composeRule.onNodeWithText("Remove", useUnmergedTree = true).assertNotClipped().assertNoMidWordBreaks()
    }

    @Test
    fun `at the largest font Hardcover metadata keeps its words`() {
        show(fontScale = 2f)

        composeRule.onNodeWithText("Hardcover metadata", useUnmergedTree = true).assertNoMidWordBreaks()
    }

    @Test
    fun `the token link says it opens the browser`() {
        show()

        composeRule
            .onNode(hasText("Get a token from Hardcover"))
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
            .label shouldBe "Open in browser"
    }

    @Test
    fun `checking a token is announced`() {
        show(status = HardcoverSourceStatus(), save = HardcoverTokenSave.Busy)

        composeRule.onNode(isLiveRegion() and hasText("Checking with Hardcover…")).assertExists()
    }

    @Test
    fun `a token Hardcover rejected is announced`() {
        show(status = HardcoverSourceStatus(apiToken = HardcoverApiTokenStatus.Rejected("simon")))

        composeRule.onNode(isLiveRegion() and hasText("Hardcover rejected this token — replace it")).assertExists()
    }

    private companion object {
        // The audited phone at the largest font is 345dp wide; the admin column's margins leave 313dp.
        val PHONE_COLUMN = 313.dp
    }
}
