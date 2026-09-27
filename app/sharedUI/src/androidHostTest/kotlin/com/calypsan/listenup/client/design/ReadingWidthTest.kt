package com.calypsan.listenup.client.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins [readingWidth]: a reading-width column is capped and centred on a tablet, and on a phone it
 * is exactly the full width it was before the cap existed. The window is tablet-sized so the 1000dp
 * host isn't clipped to Robolectric's 320dp default.
 */
@Config(qualifiers = "w1280dp-h800dp")
@RunWith(RobolectricTestRunner::class)
class ReadingWidthTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a tablet-wide window caps the column at the reading width and centres it`() {
        composeRule.setContent {
            Box(Modifier.width(1000.dp).height(400.dp)) {
                Box(Modifier.fillMaxSize().readingWidth().testTag(TAG))
            }
        }

        composeRule.onNodeWithTag(TAG).assertWidthIsEqualTo(ReadingMaxWidth)
        composeRule.onNodeWithTag(TAG).assertLeftPositionInRootIsEqualTo((1000.dp - ReadingMaxWidth) / 2)
    }

    @Test
    fun `a phone-wide window keeps the full width`() {
        composeRule.setContent {
            Box(Modifier.width(400.dp).height(400.dp)) {
                Box(Modifier.fillMaxSize().readingWidth().testTag(TAG))
            }
        }

        composeRule.onNodeWithTag(TAG).assertWidthIsEqualTo(400.dp)
        composeRule.onNodeWithTag(TAG).assertLeftPositionInRootIsEqualTo(0.dp)
    }

    @Test
    fun `the reading width is DESIGN_md's 640dp`() {
        ReadingMaxWidth shouldBe 640.dp
    }

    private companion object {
        const val TAG = "reading-column"
    }
}
