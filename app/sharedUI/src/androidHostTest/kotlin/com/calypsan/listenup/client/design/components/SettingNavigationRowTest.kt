package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.Spacing
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Pins the Material navigation row: one button named by its title, and no disclosure chevron. The
 * rows used to end in a `KeyboardArrowRight` — an iOS table idiom DESIGN.md bans on Android — and
 * a decorative icon leaves no semantics node to assert on, so the absence is pinned by geometry: the
 * title's text runs all the way to the row's inner edge, with nothing trailing it. Native graphics,
 * because legacy mode fakes text metrics and this measures where a wrapped title ends.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SettingNavigationRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the row is one button named by its title`() {
        var opened = 0
        composeRule.setContent {
            MaterialTheme {
                SettingNavigationRow(title = TITLE, subtitle = SUBTITLE, onClick = { opened++ })
            }
        }

        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
        composeRule
            .onNode(hasClickAction())
            .assert(hasText(TITLE))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        opened shouldBe 1
    }

    @Test
    fun `nothing trails the title - no disclosure chevron`() {
        composeRule.setContent {
            MaterialTheme {
                SettingNavigationRow(
                    title = LONG_TITLE,
                    onClick = {},
                    modifier = Modifier.width(ROW_WIDTH).testTag(ROW_TAG),
                )
            }
        }

        val row = composeRule.onNodeWithTag(ROW_TAG).fetchSemanticsNode().boundsInRoot
        val title = composeRule.onNodeWithText(LONG_TITLE, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val innerEdge = row.right - with(composeRule.density) { Spacing.lg.toPx() }

        // A trailing 24dp chevron plus its 14dp gap would stop the title ~38dp short of this edge.
        title.right shouldBeGreaterThanOrEqual innerEdge - 1f
    }

    private companion object {
        const val TITLE = "Open-source licenses"
        const val SUBTITLE = "View third-party licenses"
        const val LONG_TITLE = "A destination whose name is long enough to wrap onto a second line of the row"
        const val ROW_TAG = "row"
        val ROW_WIDTH = 320.dp
    }
}
