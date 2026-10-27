package com.calypsan.listenup.client.features.documentviewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.client.features.nowplaying.samplePlayerProgress
import com.calypsan.listenup.client.foldable.Fold
import com.calypsan.listenup.client.foldable.LocalFold
import com.calypsan.listenup.client.foldable.Posture
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reader puts the listening pane beside the page when the screen has room ([readerLayout]) and keeps the
 * now-playing strip under the page otherwise. The pane is Now Playing's own pieces, wired to the player's actions.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderListeningPaneTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var speedClicks = 0
    private var skipForwards = 0

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `a tablet puts the listening pane beside the page and drops the strip`() {
        setContent({ Fold.None })

        composeRule.onNodeWithTag(READER_LISTENING_PANE_TAG).assertExists()
        composeRule.onNodeWithText(STRIP_LINE).assertDoesNotExist()
        composeRule.onNodeWithText(SCRUBBER).assertExists()

        val pages = composeRule.onNodeWithTag(PAGES_TAG).getUnclippedBoundsInRoot()
        val pane = composeRule.onNodeWithTag(READER_LISTENING_PANE_TAG).getUnclippedBoundsInRoot()
        pages.right shouldBeLessThanOrEqualTo pane.left
        pane.right - pane.left shouldBe 360.dp
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `under the pane's scrubber only the chapter's times - no book sentence`() {
        setContent({ Fold.None })
        composeRule.onAllNodesWithText("% · ", substring = true).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the pane's controls reach the player`() {
        setContent({ Fold.None })

        composeRule.onNodeWithContentDescription("Skip forward 30 seconds").performClick()
        composeRule.onNodeWithContentDescription("Playback speed").performClick()

        skipForwards shouldBe 1
        speedClicks shouldBe 1
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `a phone keeps the strip under the page`() {
        setContent({ Fold.None })

        composeRule.onNodeWithTag(READER_LISTENING_PANE_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(STRIP_LINE).assertExists()
        composeRule.onNodeWithText(SCRUBBER).assertExists()
    }

    @Test
    @Config(qualifiers = "w673dp-h841dp")
    fun `half open like a book puts the page and the pane either side of the hinge`() {
        setContent(bookFold(hingeLeft = 330.dp, hingeRight = 342.dp))

        composeRule.onNodeWithText(STRIP_LINE).assertDoesNotExist()
        val pages = composeRule.onNodeWithTag(PAGES_TAG).getUnclippedBoundsInRoot()
        val pane = composeRule.onNodeWithTag(READER_LISTENING_PANE_TAG).getUnclippedBoundsInRoot()
        pages.right shouldBeLessThanOrEqualTo 330.dp - 16.dp
        pane.left shouldBeGreaterThanOrEqualTo 342.dp + 16.dp
    }

    @Test
    @Config(qualifiers = "w673dp-h841dp")
    fun `half open on a table keeps the strip`() {
        setContent({ Fold(Posture.TABLETOP, IntRect(0, 420.dp.roundToPx(), 673.dp.roundToPx(), 420.dp.roundToPx())) })

        composeRule.onNodeWithTag(READER_LISTENING_PANE_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(STRIP_LINE).assertExists()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `nothing playing keeps the page alone`() {
        setContent({ Fold.None }, state = PlayerUiState.NothingLoaded)

        composeRule.onNodeWithTag(READER_LISTENING_PANE_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(STRIP_LINE).assertDoesNotExist()
        composeRule.onNodeWithText(SCRUBBER).assertExists()
    }

    private fun bookFold(
        hingeLeft: Dp,
        hingeRight: Dp,
    ): Density.() -> Fold =
        {
            Fold(Posture.BOOK, IntRect(hingeLeft.roundToPx(), 0, hingeRight.roundToPx(), 841.dp.roundToPx()))
        }

    /** Hosts the frame under [fold], built in the test window's density so hinge bounds can be given in dp. */
    private fun setContent(
        fold: Density.() -> Fold,
        state: PlayerUiState = playing,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalFold provides LocalDensity.current.fold()) {
                MaterialTheme {
                    ReaderFrame(
                        state = state,
                        progress = { samplePlayerProgress },
                        actions =
                            ReaderPlayerActions(
                                onPrimary = {},
                                onSeek = {},
                                onSkipBack = {},
                                onSkipForward = { skipForwards++ },
                                onPreviousChapter = {},
                                onNextChapter = {},
                                onSpeedClick = { speedClicks++ },
                                onSleepClick = {},
                                onKeepListening = {},
                                onCardAction = {},
                                onRate = {},
                            ),
                        chromeVisible = true,
                        topBar = {},
                        scrubber = { Text(SCRUBBER) },
                        pages = { Box(Modifier.fillMaxSize().testTag(PAGES_TAG)) },
                    )
                }
            }
        }
    }

    private companion object {
        const val PAGES_TAG = "reader_pages"
        const val SCRUBBER = "page scrubber"

        /** The strip's mini line: only the strip shows it. */
        const val STRIP_LINE = "Bridge Four · 8m left"

        val playing = loadedPlayerState(coverPath = "/nonexistent/cover.jpg")
    }
}
