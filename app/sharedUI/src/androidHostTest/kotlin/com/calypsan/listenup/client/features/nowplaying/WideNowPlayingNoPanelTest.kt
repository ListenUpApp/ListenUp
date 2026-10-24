package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.features.nowplaying.components.CHAPTER_PANEL_TAG
import com.calypsan.listenup.client.presentation.player.PlayerMenuItem
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A short Expanded window (1000×700dp; spec §5.3, C4): no 240dp cover fits beside the panel either way, so the cover
 * stays beside the controls and Chapters stays in the settings row, opening the sheet. The series is still a link,
 * because the header no longer names it and More leaves it out.
 */
@Config(qualifiers = "w1000dp-h700dp-land")
@RunWith(RobolectricTestRunner::class)
class WideNowPlayingNoPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var chaptersOpened = 0
    private val picked = mutableListOf<PlayerMenuItem>()
    private val panelShown = mutableListOf<Boolean>()

    private fun wide() {
        composeRule.setContent {
            MaterialTheme {
                TestWideNowPlaying(
                    state = chapteredState(),
                    onChaptersClick = { chaptersOpened++ },
                    onMenuItem = { picked += it },
                    onChapterPanelShown = { panelShown += it },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `no panel, and Chapters opens the sheet`() {
        wide()
        composeRule.onNodeWithTag(CHAPTER_PANEL_TAG).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Chapters").performClick()
        chaptersOpened shouldBe 1
        panelShown.last() shouldBe false
    }

    @Test
    fun `the series is still reachable, as a link`() {
        wide()
        composeRule.onNodeWithText("The Stormlight Archive · Book 1").assertHasClickAction().performClick()
        picked shouldBe listOf(PlayerMenuItem.GoToSeries("s1"))
    }
}
