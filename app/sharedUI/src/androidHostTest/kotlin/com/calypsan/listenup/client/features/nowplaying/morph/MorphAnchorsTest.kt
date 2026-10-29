package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.nowplaying.CompactNowPlaying
import com.calypsan.listenup.client.features.nowplaying.MINI_COVER_TAG
import com.calypsan.listenup.client.features.nowplaying.NowPlayingBar
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.client.features.nowplaying.samplePlayerProgress
import com.calypsan.listenup.client.playback.PlayerPhase
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Both ends of the morph are measured: the bar in host space, Now Playing in its own untransformed sheet space. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class MorphAnchorsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var morph: PlayerMorphState
    private var sheetOffsetDp by mutableFloatStateOf(0f)
    private var density = 1f

    private fun rig() {
        composeRule.setContent {
            MaterialTheme {
                morph = rememberPlayerMorph(isExpanded = { false }, onExpand = {}, onCollapse = {})
                density = LocalDensity.current.density
                CompositionLocalProvider(LocalPlayerMorph provides morph) {
                    val state = loadedPlayerState(phase = PlayerPhase.Playing, coverPath = "/nonexistent/cover.jpg")
                    Box(Modifier.fillMaxSize().morphHost()) {
                        Column(Modifier.align(Alignment.BottomCenter)) {
                            NowPlayingBar(
                                state = state,
                                progress = { samplePlayerProgress },
                                isExpanded = false,
                                onTap = {},
                                onPrimary = {},
                                onSkipBack = {},
                                onKeepListening = {},
                                skipBackwardSec = 10,
                            )
                            Box(Modifier.fillMaxWidth().height(80.dp).morphNavigationBar())
                        }
                        Box(Modifier.fillMaxSize().graphicsLayer { translationY = sheetOffsetDp * density }) {
                            Box(Modifier.fillMaxSize().morphSheet()) {
                                CompactNowPlaying(
                                    state = state,
                                    progress = { samplePlayerProgress },
                                    onCollapse = {},
                                    onPrimary = {},
                                    onSeek = {},
                                    onSkipBack = {},
                                    onSkipForward = {},
                                    onPreviousChapter = {},
                                    onNextChapter = {},
                                    onSpeedClick = {},
                                    onSleepClick = {},
                                    onChaptersClick = {},
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
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the bar's cover is measured where it is drawn`() {
        rig()
        val drawn = composeRule.onNodeWithTag(MINI_COVER_TAG, useUnmergedTree = true).getBoundsInRoot()
        val measured = morph.anchors.barRect(MorphPart.Cover).shouldNotBeNull()
        measured.left / density shouldBe (drawn.left.value plusOrMinus 0.5f)
        measured.top / density shouldBe (drawn.top.value plusOrMinus 0.5f)
        measured.width / density shouldBe ((drawn.right - drawn.left).value plusOrMinus 0.5f)
    }

    @Test
    fun `the page's cover is the same wherever the sheet has been moved to`() {
        rig()
        val atRest = morph.anchors.pageRect(MorphPart.Cover).shouldNotBeNull()
        sheetOffsetDp = 775f
        composeRule.waitForIdle()
        morph.anchors.pageRect(MorphPart.Cover) shouldBe atRest
    }

    @Test
    fun `every flying part is measured at both ends after the first frame, and the morph is ready`() {
        MorphAnchors().isReady shouldBe false
        rig()
        MorphPart.entries.forEach { part ->
            morph.anchors.barRect(part).shouldNotBeNull()
            morph.anchors.pageRect(part).shouldNotBeNull()
        }
        BarDetail.entries.forEach { morph.anchors.barRect(it).shouldNotBeNull() }
        morph.anchors.isReady shouldBe true
        morph.anchors.navigationHeightPx shouldBe (80f * density plusOrMinus 0.5f)
        morph.anchors.barBounds.shouldNotBeNull()
    }
}
