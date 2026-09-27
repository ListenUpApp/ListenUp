package com.calypsan.listenup.client.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [compactTouchTarget]: a 32dp header control keeps its 32dp footprint in the row, yet its
 * target is a full 48dp — and a tap on the part that overhangs the footprint still lands.
 */
@RunWith(RobolectricTestRunner::class)
class TouchTargetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a compact control keeps its footprint and gains a 48dp target`() {
        var taps = 0
        composeRule.setContent {
            MaterialTheme {
                Row(Modifier.padding(24.dp).testTag(ROW)) {
                    IconButton(onClick = { taps++ }, modifier = Modifier.compactTouchTarget(footprint = 32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = LABEL, modifier = Modifier.size(20.dp))
                    }
                    Box(Modifier.size(1.dp))
                }
            }
        }

        composeRule.onNodeWithTag(ROW).assertHeightIsEqualTo(32.dp)
        val control = composeRule.onNodeWithContentDescription(LABEL)
        control.assertWidthIsEqualTo(MinTouchTarget)
        control.assertHeightIsEqualTo(MinTouchTarget)

        // 3dp in from the target's leading edge, halfway down: inside the round target, 5dp outside
        // the 32dp footprint.
        control.performTouchInput { click(Offset(3.dp.toPx(), centerY)) }
        taps shouldBe 1
    }

    private companion object {
        const val ROW = "row"
        const val LABEL = "Remove"
    }
}
