package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.features.nowplaying.components.CastConnectedButton
import com.calypsan.listenup.client.testing.assertTouchTargetAtLeast
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * §5.5.6 (PO) on Android: while casting, the output control fills `primaryContainer` with the device's name and still
 * opens the system's Cast dialog. (Play Services are absent under Robolectric, so this drives the connected branch.)
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class CastButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var opened = 0

    private fun connected() {
        composeRule.setContent {
            MaterialTheme(colorScheme = SCHEME) {
                CastConnectedButton(deviceName = "Living Room", onOpenRoutes = { opened++ })
            }
        }
    }

    @Test
    fun `it names the device, says what a tap does, and keeps a 48dp target`() {
        connected()
        composeRule.onNodeWithText("Living Room", useUnmergedTree = true).assertExists()
        composeRule
            .onNodeWithContentDescription("Cast. Playing on Living Room. Change output")
            .assertTouchTargetAtLeast()
    }

    @Test
    fun `a tap opens the system's routes`() {
        connected()
        composeRule.onNodeWithContentDescription("Cast. Playing on Living Room. Change output").performClick()
        opened shouldBe 1
    }

    @Test
    fun `it fills primaryContainer`() {
        connected()
        val image =
            composeRule
                .onNodeWithContentDescription("Cast. Playing on Living Room. Change output")
                .captureToImage()
                .toPixelMap()
        // Just inside the pill's leading edge, at mid-height: the container, clear of the glyph and the words.
        image[EDGE_INSET, image.height / 2] shouldBe SCHEME.primaryContainer
    }

    private companion object {
        val SCHEME = lightColorScheme()
        const val EDGE_INSET = 6
    }
}
