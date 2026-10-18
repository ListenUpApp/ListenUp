package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.discover.shelf
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A shelf card's book count sits in its bottom-left corner. When the whole card was clipped to the large
 * (28dp) shape, that corner's arc cut the leading digit of "8 books" away. The count must draw in full.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DiscoverShelfCardCornerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theBookCountIsNotCutByTheCardsRoundedCorner() {
        composeRule.setContent {
            ListenUpTheme(darkTheme = false) {
                Box(Modifier.background(Color.White)) {
                    DiscoverShelfCard(
                        shelf = shelf("sh1", "Cosy mysteries", bookCount = 8, coverCount = 0),
                        width = 156.dp,
                        onClick = {},
                        modifier = Modifier.testTag(CARD),
                    )
                }
            }
        }

        val pixels = composeRule.onNodeWithTag(CARD).captureToImage().toPixelMap()
        val radius = (CORNER_DP * composeRule.density.density).toInt()
        var inkInsideTheCornerCut = 0
        for (x in 0 until radius) {
            for (fromBottom in 0 until radius) {
                val dx = radius - x
                val dy = radius - fromBottom
                // Clear of the arc's anti-aliased edge, so only a pixel the clip removes outright counts.
                val outsideTheArc = dx * dx + dy * dy > (radius + EDGE) * (radius + EDGE)
                if (outsideTheArc && pixels[x, pixels.height - 1 - fromBottom].isInk()) inkInsideTheCornerCut++
            }
        }
        withClue("the book count is cut away by the card's rounded bottom-left corner") {
            inkInsideTheCornerCut shouldBeGreaterThan 0
        }
    }

    private fun Color.isInk() = red < INK && green < INK && blue < INK

    private companion object {
        const val EDGE = 2
        const val INK = 0.8f
        const val CARD = "shelf-card"
        const val CORNER_DP = 28f
    }
}
