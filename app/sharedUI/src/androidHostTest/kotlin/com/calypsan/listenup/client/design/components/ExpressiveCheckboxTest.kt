package com.calypsan.listenup.client.design.components

import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [ExpressiveCheckbox]'s checked state for TalkBack. The metadata-match review decides which
 * fields get applied by these boxes, and before this they announced nothing at all — neither the
 * interactive tile nor the read-only indicator a parent row owns.
 */
@RunWith(RobolectricTestRunner::class)
class ExpressiveCheckboxTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a read-only box still reports its state`() {
        composeRule.setContent {
            MaterialTheme {
                ExpressiveCheckbox(checked = true, modifier = Modifier.testTag(TAG))
            }
        }

        composeRule.onNodeWithTag(TAG).assertIsOn()
    }

    @Test
    fun `a read-only unchecked box reports off`() {
        composeRule.setContent {
            MaterialTheme {
                ExpressiveCheckbox(checked = false, modifier = Modifier.testTag(TAG))
            }
        }

        composeRule.onNodeWithTag(TAG).assertIsOff()
    }

    @Test
    fun `an interactive box is a checkbox that follows checked`() {
        var checked by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                ExpressiveCheckbox(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    modifier = Modifier.testTag(TAG),
                )
            }
        }

        composeRule
            .onNodeWithTag(TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertIsOff()
            .performClick()

        checked shouldBe true
        composeRule.onNodeWithTag(TAG).assertIsOn()
    }

    @Test
    fun `an interactive box keeps its 26dp tile but takes a 48dp touch target`() {
        composeRule.setContent {
            MaterialTheme {
                Row(Modifier.testTag(ROW)) {
                    ExpressiveCheckbox(
                        checked = false,
                        onCheckedChange = {},
                        modifier = Modifier.testTag(TAG),
                    )
                }
            }
        }

        composeRule.onNodeWithTag(TAG).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        // The row the box sits in doesn't grow: the target overlaps the row's own padding.
        composeRule.onNodeWithTag(ROW).assertHeightIsEqualTo(26.dp)
    }

    private companion object {
        const val TAG = "expressive_checkbox"
        const val ROW = "row"
    }
}
