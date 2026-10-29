package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.nowplaying.EntryMotion
import com.calypsan.listenup.client.features.nowplaying.MINI_BODY_TAG
import com.calypsan.listenup.client.features.nowplaying.NO_COVER
import com.calypsan.listenup.client.features.nowplaying.NowPlayingBar
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.client.features.nowplaying.samplePlayerProgress
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Going from the shell to a detail screen hands the bar over: the shell's bar, placed last during its exit, then
 * leaving, must not take the detail bar's measurements with it.
 */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class MorphBarHandOverTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var morph: PlayerMorphState
    private var density = 1f
    private var showsShellBar by mutableStateOf(true)
    private var showsDetailBar by mutableStateOf(false)
    private var shellLift by mutableStateOf(80)

    @Test
    fun `after the shell's bar leaves, the morph measures the detail screen's bar and its drag works`() {
        composeRule.setContent {
            MaterialTheme {
                density = LocalDensity.current.density
                morph = rememberPlayerMorph(isExpanded = { false }, onExpand = {}, onCollapse = {})
                CompositionLocalProvider(LocalPlayerMorph provides morph) {
                    PlayerMorphSync(morph = morph, isExpanded = false, entryMotion = EntryMotion.Morph)
                    Box(Modifier.fillMaxSize().morphHost()) {
                        // Two subtrees, as the shell's bar (inside the navigation display) and the host's are: one
                        // changing never re-places the other.
                        Box(Modifier.fillMaxSize()) {
                            if (showsShellBar) Bar(Modifier.align(Alignment.BottomCenter).padding(bottom = shellLift.dp))
                        }
                        Box(Modifier.fillMaxSize()) {
                            if (showsDetailBar) Bar(Modifier.align(Alignment.BottomCenter).testTag(DETAIL_BAR))
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        showsDetailBar = true
        composeRule.waitForIdle()
        // The outgoing shell bar is placed again (its exit transition moves it), after the detail bar.
        shellLift = 96
        composeRule.waitForIdle()
        showsShellBar = false
        composeRule.waitForIdle()

        val detail = composeRule.onNodeWithTag(DETAIL_BAR).getBoundsInRoot()
        val measured = morph.anchors.barBounds.shouldNotBeNull()
        measured.top / density shouldBe (detail.top.value plusOrMinus 0.5f)
        composeRule.onNodeWithTag(MINI_BODY_TAG).performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, -40f * density), delayMillis = 40) }
        }
        composeRule.waitForIdle()
        morph.progress shouldBeGreaterThan 0f
    }

    @androidx.compose.runtime.Composable
    private fun Bar(modifier: Modifier) {
        NowPlayingBar(
            state = loadedPlayerState(coverPath = NO_COVER),
            progress = { samplePlayerProgress },
            isExpanded = false,
            onTap = {},
            onPrimary = {},
            onSkipBack = {},
            onKeepListening = {},
            skipBackwardSec = 10,
            modifier = modifier,
        )
    }

    private companion object {
        const val DETAIL_BAR = "detail-bar"
    }
}
