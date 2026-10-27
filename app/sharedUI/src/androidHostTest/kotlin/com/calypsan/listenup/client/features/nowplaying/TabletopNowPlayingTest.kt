package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.foldable.Fold
import com.calypsan.listenup.client.foldable.LocalFold
import com.calypsan.listenup.client.foldable.Posture
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A foldable half open on a table (the Pixel Fold's inner screen, hinge across the middle) splits
 * Now Playing at the fold: the cover and the title stand up on the top half, the scrubber and the
 * transport lie on the bottom half under the user's hand, and nothing sits across the crease.
 */
@Config(qualifiers = "w673dp-h841dp")
@RunWith(RobolectricTestRunner::class)
class TabletopNowPlayingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the cover and title sit above the hinge and the transport below it`() {
        setContent(hingeTop = 400.dp, hingeBottom = 424.dp)

        val cover = composeRule.onNodeWithTag(NOW_PLAYING_COVER_TAG).getUnclippedBoundsInRoot()
        // The cover's own fallback also prints the title; this is the line under it.
        val title =
            composeRule
                .onNode(hasText(activeState.book.title) and !hasAnyAncestor(hasTestTag(NOW_PLAYING_COVER_TAG)))
                .getUnclippedBoundsInRoot()
        val chapter = composeRule.onNodeWithText("Prologue: To Kill").getUnclippedBoundsInRoot()
        val play = composeRule.onNodeWithContentDescription("Play").getUnclippedBoundsInRoot()
        val skipForward = composeRule.onNodeWithContentDescription("Skip forward 30 seconds").getUnclippedBoundsInRoot()
        val chapters = composeRule.onNodeWithContentDescription("Chapters").getUnclippedBoundsInRoot()

        cover.bottom shouldBeLessThanOrEqualTo 400.dp
        title.bottom shouldBeLessThanOrEqualTo 400.dp
        chapter.bottom shouldBeLessThanOrEqualTo 400.dp
        play.top shouldBeGreaterThanOrEqualTo 424.dp
        skipForward.top shouldBeGreaterThanOrEqualTo 424.dp
        chapters.top shouldBeGreaterThanOrEqualTo 424.dp
        chapters.bottom shouldBeLessThanOrEqualTo WINDOW_HEIGHT
    }

    @Test
    fun `under the scrubber only the chapter's times - no book sentence`() {
        setContent(hingeTop = 400.dp, hingeBottom = 424.dp)
        composeRule.onAllNodesWithText("% · ", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a hairline fold still keeps the halves apart`() {
        setContent(hingeTop = 420.dp, hingeBottom = 420.dp)

        composeRule.onNodeWithTag(NOW_PLAYING_COVER_TAG).getUnclippedBoundsInRoot().bottom shouldBeLessThanOrEqualTo
            420.dp
        composeRule.onNodeWithContentDescription("Play").getUnclippedBoundsInRoot().top shouldBeGreaterThanOrEqualTo
            420.dp
    }

    private fun setContent(
        hingeTop: Dp,
        hingeBottom: Dp,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            val hinge =
                with(density) {
                    IntRect(0, hingeTop.roundToPx(), 673.dp.roundToPx(), hingeBottom.roundToPx())
                }
            CompositionLocalProvider(LocalFold provides Fold(Posture.TABLETOP, hinge)) {
                MaterialTheme {
                    NowPlayingScreen(
                        state = activeState,
                        progress = { progress },
                        onCollapse = {},
                        onPrimary = {},
                        onSeek = {},
                        onSkipBack = {},
                        onSkipForward = {},
                        onPreviousChapter = {},
                        onNextChapter = {},
                        onSpeedClick = {},
                        onChaptersClick = {},
                        onSeekToChapter = {},
                        onSleepTimerClick = {},
                        onGoToContributor = {},
                        onShowAuthorPicker = {},
                        onShowNarratorPicker = {},
                        onMenuItem = {},
                        onCardAction = {},
                        onRate = {},
                    )
                }
            }
        }
    }

    private companion object {
        val WINDOW_HEIGHT = 841.dp

        val progress = samplePlayerProgress

        // As before the shared model: paused, no contributors, chapter 1 titled.
        val activeState =
            loadedPlayerState(
                title = "The Way of Kings",
                phase = PlayerPhase.Paused,
                primary = PrimaryControl.Play,
                chapterTitle = "Prologue: To Kill",
                coverPath = "/nonexistent/cover.jpg",
            ).let { it.copy(book = it.book.copy(authors = emptyList(), narrators = emptyList())) }
    }
}
