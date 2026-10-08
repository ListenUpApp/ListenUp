package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.testing.Windows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins [SectionColumns]: the column count follows the width but never outruns the sections, and each
 * section drops into the shortest column.
 */
@Config(qualifiers = Windows.TABLET)
@RunWith(RobolectricTestRunner::class)
class SectionColumnsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a wide block splits into as many columns as fit and fills the shortest first`() {
        // 1128dp fits three 360dp columns with two 24dp gutters.
        composeRule.setContent {
            Box(Modifier.width(1128.dp)) {
                SectionColumns {
                    section { Tile("a", 300.dp) }
                    section { Tile("b", 100.dp) }
                    section { Tile("c", 200.dp) }
                    section { Tile("d", 50.dp) }
                }
            }
        }

        composeRule.onNodeWithTag("a").assertWidthIsEqualTo(360.dp).assertLeftPositionInRootIsEqualTo(0.dp)
        composeRule.onNodeWithTag("b").assertLeftPositionInRootIsEqualTo(384.dp).assertTopPositionInRootIsEqualTo(0.dp)
        composeRule.onNodeWithTag("c").assertLeftPositionInRootIsEqualTo(768.dp).assertTopPositionInRootIsEqualTo(0.dp)
        // The middle column is the shortest, so the fourth section lands under b, a section gap down.
        composeRule.onNodeWithTag("d").assertLeftPositionInRootIsEqualTo(384.dp).assertTopPositionInRootIsEqualTo(124.dp)
    }

    @Test
    fun `two sections share the width instead of leaving an empty column`() {
        composeRule.setContent {
            Box(Modifier.width(1128.dp)) {
                SectionColumns {
                    section { Tile("a", 100.dp) }
                    section { Tile("b", 100.dp) }
                }
            }
        }

        composeRule.onNodeWithTag("a").assertWidthIsEqualTo(552.dp)
        composeRule.onNodeWithTag("b").assertLeftPositionInRootIsEqualTo(576.dp)
    }

    @Test
    fun `a width under two columns stacks the sections full width`() {
        composeRule.setContent {
            Box(Modifier.width(600.dp)) {
                SectionColumns {
                    section { Tile("a", 100.dp) }
                    section { Tile("b", 100.dp) }
                }
            }
        }

        composeRule.onNodeWithTag("a").assertWidthIsEqualTo(600.dp)
        composeRule.onNodeWithTag("b").assertLeftPositionInRootIsEqualTo(0.dp).assertTopPositionInRootIsEqualTo(124.dp)
    }

    @Composable
    private fun Tile(
        tag: String,
        height: Dp,
    ) {
        Box(Modifier.testTag(tag).fillMaxWidth().height(height))
    }
}
