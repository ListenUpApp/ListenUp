package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.ShelfCover
import com.calypsan.listenup.core.ShelfId
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone shelf tile's "6 books · 74h" line sits in the tile's bottom-start corner. Clipping the whole tile to its
 * rounded shape cut the first glyphs on the arc; the line must draw exactly as the same text does on its own.
 */
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
// Native graphics: the comparison is of real glyph pixels.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class ShelfTileCornerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the meta line's first glyphs are not cut by the tile's rounded corner`() {
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.background(Color.White)) {
                    MyShelvesSection(listOf(shelf()), HomeWidth.Compact, onOpenShelf = {}, onCreateShelf = {})
                    Text(
                        text = META,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(REFERENCE),
                    )
                }
            }
        }
        val inTile =
            composeRule
                .onNode(hasText(META) and !hasTestTag(REFERENCE), useUnmergedTree = true)
                .captureToImage()
        val alone = composeRule.onNodeWithTag(REFERENCE).captureToImage()

        val expected = inkInLeadingStrip(alone)
        expected shouldBeGreaterThan 0
        withClue("ink pixels in the meta line's leading strip, in the tile vs on its own") {
            inkInLeadingStrip(inTile) shouldBe expected
        }
    }

    /** Dark pixels in the leftmost [LEADING_STRIP_PX] columns: the glyphs a rounded bottom-start corner would cut. */
    private fun inkInLeadingStrip(image: ImageBitmap): Int {
        val pixels = image.toPixelMap()
        var ink = 0
        for (x in 0 until minOf(LEADING_STRIP_PX, pixels.width)) {
            for (y in 0 until pixels.height) {
                if (pixels[x, y].luminance() < INK_LUMINANCE) ink++
            }
        }
        return ink
    }

    private fun shelf() =
        Shelf(
            id = ShelfId("s-winter"),
            name = "Winter reads",
            description = null,
            isPrivate = false,
            ownerId = "me",
            ownerDisplayName = "Simon",
            bookCount = 6,
            totalDurationSeconds = 266_400L,
            createdAtMs = 0,
            updatedAtMs = 0,
            covers = listOf(ShelfCover("b1", coverPath = "/tmp/cover-b1.webp", coverHash = "h1")),
        )

    private companion object {
        const val META = "6 books · 74h"
        const val REFERENCE = "reference-meta"
        const val LEADING_STRIP_PX = 24
        const val INK_LUMINANCE = 0.6f
    }
}
