package com.calypsan.listenup.client.features.genredestination

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.Spacing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [FacetHeroScaffold]'s edge bleed: inside a parent inset by [Spacing.screenMargin] (the facet
 * grids' content padding), the hero composes without crashing and its tint reaches the parent's
 * edges, so its content sits one margin in from the screen edge rather than two.
 */
@RunWith(RobolectricTestRunner::class)
class FacetHeroScaffoldTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the hero bleeds past its parent's margins without a negative padding`() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(SCREEN_WIDTH).padding(horizontal = Spacing.screenMargin)) {
                    FacetHeroScaffold(hue = "#3A5F8F", icon = Icons.Outlined.Mood, onBackClick = {}) {
                        Box(Modifier.fillMaxWidth().height(1.dp).testTag(CONTENT))
                    }
                }
            }
        }

        val content = composeRule.onNodeWithTag(CONTENT)
        content.assertLeftPositionInRootIsEqualTo(Spacing.screenMargin)
        content.assertWidthIsEqualTo(SCREEN_WIDTH - Spacing.screenMargin * 2)
    }

    private companion object {
        const val CONTENT = "hero-content"
        val SCREEN_WIDTH = 300.dp // inside Robolectric's default 320dp screen
    }
}
