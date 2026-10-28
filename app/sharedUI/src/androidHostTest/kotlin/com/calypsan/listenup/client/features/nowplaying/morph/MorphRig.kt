package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.MutableTransitionState
import com.calypsan.listenup.client.features.nowplaying.NowPlayingLayout
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.LocalCompositionProbe
import com.calypsan.listenup.client.design.haptics.Haptics
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.motion.LocalReduceMotion
import com.calypsan.listenup.client.design.motion.LocalTouchExplorationActive
import com.calypsan.listenup.client.features.nowplaying.EntryMotion
import com.calypsan.listenup.client.features.nowplaying.NowPlayingBar
import com.calypsan.listenup.client.features.nowplaying.NowPlayingScreen
import com.calypsan.listenup.client.features.nowplaying.samplePlayerProgress
import com.calypsan.listenup.client.features.shell.ShellBottomBar
import com.calypsan.listenup.client.presentation.player.PlayerSheetState
import com.calypsan.listenup.client.presentation.player.PlayerUiState

/** The stand-in navigation bar under the phone bar. */
internal const val RIG_NAVIGATION_TAG = "rig-navigation"

/**
 * The phone shell around the morph, built from the same pieces `NowPlayingHost` stacks: the bar above a navigation
 * bar, the scrim, Now Playing while the morph is off the bar, and the flying parts above it. The destination is a real
 * [PlayerSheetState].
 */
internal class MorphRig(
    private val composeRule: ComposeContentTestRule,
) {
    val sheet = PlayerSheetState()
    lateinit var morph: PlayerMorphState
    var density = 1f
    var primaryPresses = 0
    var tapOpens = 0
    val compositions = mutableMapOf<String, Int>()
    val visibility = MutableTransitionState(false)
    var usesPhoneBar by mutableStateOf(true)
    var layout by mutableStateOf(NowPlayingLayout.Stacked)

    /** What the player shows; a spec can unload the book mid-gesture. */
    var state: PlayerUiState by mutableStateOf(PlayerUiState.NothingLoaded)
    var touchExploration by mutableStateOf(false)
    var home by mutableStateOf(BarHome.Shell)
    var entryMotion = EntryMotion.Morph

    fun show(
        state: PlayerUiState.Loaded,
        reduceMotion: Boolean = false,
        haptics: Haptics? = null,
        probeInputMode: @Composable () -> Unit = {},
        inputMode: InputMode? = null,
    ) {
        this.state = state
        composeRule.setContent {
            probeInputMode()
            val probe: (String) -> Unit = { compositions[it] = (compositions[it] ?: 0) + 1 }
            CompositionLocalProvider(
                LocalReduceMotion provides reduceMotion,
                LocalCompositionProbe provides probe,
                LocalTouchExplorationActive provides touchExploration,
                LocalInputModeManager provides (inputMode?.let(::FixedInputMode) ?: LocalInputModeManager.current),
            ) {
                MaterialTheme {
                    if (haptics != null) {
                        CompositionLocalProvider(LocalHaptics provides haptics) { Stage() }
                    } else {
                        Stage()
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun Stage() {
        val state = state
        density = LocalDensity.current.density
        morph =
            rememberPlayerMorph(
                isExpanded = { sheet.expanded.value },
                onExpand = sheet::expand,
                onCollapse = sheet::collapse,
            )
        val isExpanded by sheet.expanded.collectAsState()
        CompositionLocalProvider(LocalPlayerMorph provides morph) {
            entryMotion =
                rememberEntryMotion(
                    usesPhoneBar = usesPhoneBar,
                    layout = layout,
                    home = home,
                    isExpanded = isExpanded,
                    isPresented = visibility.currentState || visibility.targetState,
                )
            Box(Modifier.fillMaxSize().morphHost()) {
                Column(Modifier.align(Alignment.BottomCenter)) {
                    ShellBottomBar(
                        nowPlayingContent = {
                            NowPlayingBar(
                                state = state,
                                progress = { samplePlayerProgress },
                                isExpanded = isExpanded,
                                onTap = {
                                    tapOpens++
                                    sheet.expand()
                                },
                                onPrimary = { primaryPresses++ },
                                onSkipBack = {},
                                onKeepListening = {},
                                skipBackwardSec = 10,
                            )
                        },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .morphNavigationSlide()
                                .testTag(RIG_NAVIGATION_TAG),
                        )
                    }
                }
                NowPlayingStage(
                    morph = morph,
                    entryMotion = entryMotion,
                    isExpanded = isExpanded,
                    loaded = state as? PlayerUiState.Loaded,
                    progress = { samplePlayerProgress },
                    skipBackwardSec = 10,
                    visibility = visibility,
                ) { shown ->
                    NowPlayingScreen(
                        state = shown,
                        progress = { samplePlayerProgress },
                        onCollapse = sheet::collapse,
                        onPrimary = { primaryPresses++ },
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
}

/** An input mode that stays put, for specs that need touch mode, which Robolectric never enters. */
private class FixedInputMode(
    override val inputMode: InputMode,
) : InputModeManager {
    override fun requestInputMode(inputMode: InputMode): Boolean = false
}
