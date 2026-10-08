package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.testing.AtFontScale
import com.calypsan.listenup.client.testing.assertNotClipped
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.unit.height

/**
 * The canonical button grows with its label. It used to be a fixed 56dp, so at the largest font "Apply selected
 * metadata" wrapped and lost its second line, and "Remove" was cut to "Re".
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class ListenUpButtonLargeTextTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun button(filled: Boolean) {
        composeRule.setContent {
            AtFontScale {
                MaterialTheme {
                    Box(Modifier.width(300.dp)) {
                        ListenUpButton(
                            text = APPLY,
                            onClick = {},
                            filled = filled,
                            leadingIcon = Icons.Outlined.Check,
                            modifier = Modifier.testTag(BUTTON),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a filled button grows to hold a wrapped label`() {
        button(filled = true)
        assertGrowsAroundItsLabel()
    }

    @Test
    fun `an outlined button grows to hold a wrapped label`() {
        button(filled = false)
        assertGrowsAroundItsLabel()
    }

    @Test
    fun `at the default font the button keeps its 56dp height`() {
        composeRule.setContent {
            MaterialTheme { ListenUpButton(text = "Save", onClick = {}, modifier = Modifier.testTag(BUTTON)) }
        }
        val bounds = composeRule.onNodeWithTag(BUTTON).getUnclippedBoundsInRoot()
        bounds.height shouldBeGreaterThanOrEqualTo 56.dp
        bounds.height shouldBeLessThanOrEqualTo 57.dp
    }

    private fun assertGrowsAroundItsLabel() {
        val label = composeRule.onNodeWithText(APPLY, useUnmergedTree = true).assertNotClipped()
        val button = composeRule.onNodeWithTag(BUTTON).getUnclippedBoundsInRoot()
        val text = label.getUnclippedBoundsInRoot()
        button.height shouldBeGreaterThan 56.dp
        text.bottom shouldBeLessThanOrEqualTo button.bottom
    }

    private companion object {
        const val APPLY = "Apply selected metadata"
        const val BUTTON = "button"
    }
}
