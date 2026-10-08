package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.testing.AtFontScale
import com.calypsan.listenup.client.testing.assertNoMidWordBreaks
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.unit.width

/**
 * A [SettingRow]'s text comes first. At the largest font the trailing control used to be measured first and the
 * title got what was left — "Last synced 1m ago" one letter per line, "Hardcove / r metadata". Native graphics,
 * because legacy Robolectric fakes glyph widths and every line would fit.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SettingRowLargeTextTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun syncLine(fontScale: Float) {
        composeRule.setContent {
            AtFontScale(fontScale) {
                MaterialTheme {
                    Box(Modifier.width(PHONE_WIDTH)) {
                        SettingRow(
                            title = SYNCED,
                            icon = Icons.Outlined.Category,
                            trailing = { Button(onClick = {}, modifier = Modifier.testTag(ACTION)) { Text(SYNC_NOW) } },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `at the largest font the title keeps its words and the action moves beneath it`() {
        syncLine(fontScale = 2f)

        val title = composeRule.onNodeWithText(SYNCED).assertNoMidWordBreaks().getUnclippedBoundsInRoot()
        val action = composeRule.onNodeWithTag(ACTION).getUnclippedBoundsInRoot()
        action.top shouldBeGreaterThanOrEqualTo title.bottom
        // The title has the row's width, not the sliver a 167dp button left it.
        title.width shouldBeGreaterThan PHONE_WIDTH / 2
    }

    @Test
    fun `at the default font the action stays beside the title`() {
        syncLine(fontScale = 1f)

        val title = composeRule.onNodeWithText(SYNCED).getUnclippedBoundsInRoot()
        val action = composeRule.onNodeWithTag(ACTION).getUnclippedBoundsInRoot()
        action.left shouldBeGreaterThanOrEqualTo title.right
        action.top shouldBeLessThan title.bottom
    }

    @Test
    fun `at the largest font a switch row never breaks its title mid-word`() {
        composeRule.setContent {
            AtFontScale {
                MaterialTheme {
                    Box(Modifier.width(ADMIN_ROW_WIDTH)) {
                        SettingToggleRow(
                            title = METADATA,
                            subtitle = METADATA_DETAIL,
                            icon = Icons.Outlined.Category,
                            checked = true,
                            onCheckedChange = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText(METADATA, useUnmergedTree = true).assertNoMidWordBreaks()
        composeRule.onNodeWithText(METADATA_DETAIL, useUnmergedTree = true).assertNoMidWordBreaks()
    }

    @Test
    fun `at the largest font the decorative tile steps aside and the text starts at the row's edge`() {
        tiledRow(fontScale = 2f)
        composeRule.onNodeWithText(METADATA, useUnmergedTree = true).getUnclippedBoundsInRoot().left shouldBe Spacing.lg
    }

    @Test
    fun `at the default font the tile leads the row`() {
        tiledRow(fontScale = 1f)
        composeRule.onNodeWithText(METADATA, useUnmergedTree = true).getUnclippedBoundsInRoot().left shouldBeGreaterThan Spacing.lg
    }

    private fun tiledRow(fontScale: Float) {
        composeRule.setContent {
            AtFontScale(fontScale) {
                MaterialTheme {
                    Box(Modifier.width(ADMIN_ROW_WIDTH)) {
                        SettingRow(title = METADATA, icon = Icons.Outlined.Category)
                    }
                }
            }
        }
    }

    private companion object {
        const val SYNCED = "Last synced 1m ago"
        const val SYNC_NOW = "Sync now"
        const val ACTION = "action"
        const val METADATA = "Hardcover metadata"
        const val METADATA_DETAIL = "Offer Hardcover's moods, genres, series and descriptions when you match a book"

        // The audited phone at 624dpi density: 345dp wide; the admin column leaves its rows 296dp.
        val PHONE_WIDTH = 345.dp
        val ADMIN_ROW_WIDTH = 296.dp
    }
}
