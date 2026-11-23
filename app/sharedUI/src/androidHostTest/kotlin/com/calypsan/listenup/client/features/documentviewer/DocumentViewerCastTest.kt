package com.calypsan.listenup.client.features.documentviewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Cast left Now Playing's settings row for the top bar. The reader's listening pane drew that row too, so the
 * reader is not stranded: with a book loaded, Cast sits in the viewer's own top bar, after its page-grid toggle.
 * The bar carries no More button: it would open an empty menu.
 */
@Config(qualifiers = "w1280dp-h800dp")
@RunWith(RobolectricTestRunner::class)
class DocumentViewerCastTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun bar(cast: (@Composable () -> Unit)?) {
        composeRule.setContent {
            MaterialTheme {
                ReaderTopBar(title = "The Way of Kings", onBack = {}, chromeVisible = true, onGrid = {}, cast = cast)
            }
        }
    }

    @Test
    fun `with a book loaded the viewer's top bar has Cast after the grid toggle`() {
        bar(cast = { Box(Modifier.size(48.dp).testTag(CAST_SLOT)) })
        val cast = composeRule.onNodeWithTag(CAST_SLOT).getUnclippedBoundsInRoot()
        val grid = composeRule.onNodeWithContentDescription("Toggle grid view").getUnclippedBoundsInRoot()
        (grid.right <= cast.left) shouldBe true
        abs(((cast.top + cast.bottom) / 2 - (grid.top + grid.bottom) / 2).value) shouldBeLessThanOrEqualTo 1f
    }

    @Test
    fun `the top bar offers no More button with nothing behind it`() {
        bar(cast = { Box(Modifier.size(48.dp).testTag(CAST_SLOT)) })
        composeRule.onNodeWithContentDescription("More options").assertDoesNotExist()
    }

    @Test
    fun `with nothing loaded there is no Cast`() {
        bar(cast = null)
        composeRule.onNodeWithTag(CAST_SLOT).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Toggle grid view").assertExists()
    }

    private companion object {
        const val CAST_SLOT = "cast-slot"
    }
}
