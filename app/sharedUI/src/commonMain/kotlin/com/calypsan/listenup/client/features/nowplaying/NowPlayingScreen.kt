package com.calypsan.listenup.client.features.nowplaying

import com.calypsan.listenup.client.features.nowplaying.morph.dragToClose
import androidx.compose.ui.graphics.Color
import com.calypsan.listenup.client.presentation.player.MiniPlayerLines
import com.calypsan.listenup.client.presentation.player.MiniPlayerLine
import com.calypsan.listenup.client.features.nowplaying.morph.morphSheetLayer
import com.calypsan.listenup.client.features.nowplaying.morph.morphSheet
import com.calypsan.listenup.client.features.nowplaying.morph.LocalPlayerMorph
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.calypsan.listenup.client.design.motion.PredictiveBackEdgeMargin
import com.calypsan.listenup.client.design.motion.predictiveBackPreview
import com.calypsan.listenup.client.design.util.BackGestureEdge
import com.calypsan.listenup.client.design.util.PlatformPredictiveBackHandler
import com.calypsan.listenup.client.foldable.LocalFold
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerMenuItem
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_now_playing
import org.jetbrains.compose.resources.stringResource

// TV ambient mode: fade controls out after this idle period.
private const val TV_AMBIENT_DELAY_MS = 15_000L

/**
 * Full screen Now Playing view.
 *
 * Hosts the screen-level chrome — predictive-back dismissal, drag-to-dismiss, and TV ambient
 * fade — then dispatches the body to an adaptive layout ([nowPlayingLayout]): [TabletopNowPlaying]
 * split at the hinge on a foldable half open on a table, [WideNowPlaying] on wide or short windows
 * and in book posture, and [CompactNowPlaying] everywhere else.
 *
 * @param onSeekToChapter Play from the chapter at this zero-based index: a row of the wide layout's chapter panel.
 * @param onChapterPanelShown Whether the wide layout's chapter panel is on screen, each time that changes.
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
fun NowPlayingScreen(
    state: PlayerUiState.Loaded,
    progress: () -> PlayerProgress,
    onCollapse: () -> Unit,
    onPrimary: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSpeedClick: () -> Unit,
    onChaptersClick: () -> Unit,
    onSeekToChapter: (Int) -> Unit,
    onSleepTimerClick: () -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onMenuItem: (PlayerMenuItem) -> Unit,
    onCardAction: (PlayerCardAction) -> Unit,
    onRate: (Int) -> Unit,
    isTv: Boolean = false,
    modifier: Modifier = Modifier,
    onChapterPanelShown: (Boolean) -> Unit = {},
) {
    // Predictive back: track the gesture's progress and edge to preview the dismissal — Material's
    // full-screen preview (predictiveBackPreview), which shrinks and drifts but never fades.
    val backProgress = remember { Animatable(0f) }
    var backEdge by remember { mutableStateOf(BackGestureEdge.None) }
    // Gate the handler until the screen has fully entered composition. The composable is
    // reachable during the AnimatedVisibility enter-transition, so enabling immediately would
    // let a back gesture fire before the screen is presented, creating a jarring mid-slide
    // dismiss. Flipping `presented` on the first composition frame is the lightest safe guard.
    var presented by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { presented = true }
    PlatformPredictiveBackHandler(enabled = presented) { gesture ->
        var committed = false
        try {
            gesture.collect { frame ->
                backEdge = frame.edge
                backProgress.snapTo(frame.progress)
            }
            committed = true
            onCollapse()
        } finally {
            // Gesture abandoned (the progress flow ends in cancellation) — rewind the dismissal
            // animation, NonCancellable so the rewind still runs inside the cancelled handler. On
            // commit the screen is already exiting, so progress is intentionally left as-is to
            // avoid a scale pop mid exit-transition.
            if (!committed) withContext(NonCancellable) { backProgress.snapTo(0f) }
        }
    }

    // TV ambient mode: fade out controls after inactivity. Each interaction bumps a tick that
    // re-keys the inactivity timer — a monotonically increasing counter is all the key needs.
    var isAmbientMode by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }

    fun resetAmbient() {
        interactionTick++
        isAmbientMode = false
    }

    // Track chapter changes to exit ambient mode
    val currentChapterTitle = state.chapter.title
    LaunchedEffect(currentChapterTitle) {
        resetAmbient()
    }

    // Inactivity timer for TV
    if (isTv) {
        LaunchedEffect(interactionTick) {
            kotlinx.coroutines.delay(TV_AMBIENT_DELAY_MS)
            isAmbientMode = true
        }
    }

    val ambientAlpha by animateFloatAsState(
        targetValue = if (isTv && isAmbientMode) 0f else 1f,
        // Bespoke: a deliberately slow, even dim into TV ambient mode, like a sleep fade.
        animationSpec = tween(durationMillis = 1000),
        label = "ambientAlpha",
    )

    // The corners the sheet rounds toward as the back preview shrinks it off the window's edges.
    val backPreviewCorner = MaterialTheme.shapes.extraLarge.topStart
    val fold = LocalFold.current
    val layout = nowPlayingLayout(currentWindowAdaptiveInfoV2().windowSizeClass, fold)
    val nowPlaying = stringResource(Res.string.player_now_playing)
    val morph = LocalPlayerMorph.current
    val surfaceColour = MaterialTheme.colorScheme.surface
    val barColour =
        if (sheetStartsTinted(state)) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }

    Surface(
        modifier =
            modifier
                .fillMaxSize()
                .semantics { paneTitle = nowPlaying }
                .onKeyEvent {
                    if (isTv) resetAmbient()
                    false // don't consume
                }.graphicsLayer {
                    val preview =
                        predictiveBackPreview(
                            progress = backProgress.value,
                            edge = backEdge,
                            widthPx = size.width,
                            edgeMarginPx = PredictiveBackEdgeMargin.toPx(),
                        )
                    translationX = preview.translationX
                    scaleX = preview.scale
                    scaleY = preview.scale
                    shape = RoundedCornerShape(backPreviewCorner.toPx(size, this) * preview.cornerFraction)
                    clip = preview.cornerFraction > 0f
                    // Ambient fade (TV idle) only; the back preview stays opaque, so the page beneath
                    // never shows through the player. With no TV ambient this is 1f.
                    alpha = ambientAlpha * preview.alpha
                }.morphSheetLayer(morph, barColour = barColour, surfaceColour = surfaceColour)
                .morphSheet()
                // The morph's drag-to-close where Now Playing morphs; wide windows fade through and do not drag.
                .then(if (morph?.entryMotion == EntryMotion.Morph) Modifier.dragToClose(morph) else Modifier),
        // The sheet draws its own colour (morphSheetLayer), which runs from the bar's while it morphs.
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        when (layout) {
            NowPlayingLayout.Tabletop -> {
                TabletopNowPlaying(
                    state = state,
                    progress = progress,
                    // nowPlayingLayout only answers Tabletop when the fold has bounds.
                    hingeBounds = requireNotNull(fold.hingeBounds),
                    onCollapse = onCollapse,
                    onPrimary = onPrimary,
                    onSeek = onSeek,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    onSpeedClick = onSpeedClick,
                    onSleepClick = onSleepTimerClick,
                    onChaptersClick = onChaptersClick,
                    onGoToContributor = onGoToContributor,
                    onShowAuthorPicker = onShowAuthorPicker,
                    onShowNarratorPicker = onShowNarratorPicker,
                    onMenuItem = onMenuItem,
                    onCardAction = onCardAction,
                    onRate = onRate,
                )
            }

            NowPlayingLayout.SideBySide -> {
                WideNowPlaying(
                    state = state,
                    progress = progress,
                    onCollapse = onCollapse,
                    onPrimary = onPrimary,
                    onSeek = onSeek,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    onSpeedClick = onSpeedClick,
                    onSleepClick = onSleepTimerClick,
                    onChaptersClick = onChaptersClick,
                    onSeekToChapter = onSeekToChapter,
                    onGoToContributor = onGoToContributor,
                    onShowAuthorPicker = onShowAuthorPicker,
                    onShowNarratorPicker = onShowNarratorPicker,
                    onMenuItem = onMenuItem,
                    onCardAction = onCardAction,
                    onRate = onRate,
                    onChapterPanelShown = onChapterPanelShown,
                )
            }

            NowPlayingLayout.Stacked -> {
                CompactNowPlaying(
                    state = state,
                    progress = progress,
                    onCollapse = onCollapse,
                    onPrimary = onPrimary,
                    onSeek = onSeek,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    onSpeedClick = onSpeedClick,
                    onSleepClick = onSleepTimerClick,
                    onChaptersClick = onChaptersClick,
                    onGoToContributor = onGoToContributor,
                    onShowAuthorPicker = onShowAuthorPicker,
                    onShowNarratorPicker = onShowNarratorPicker,
                    onMenuItem = onMenuItem,
                    onCardAction = onCardAction,
                    onRate = onRate,
                )
            }
        }
    }
}

/** Whether the bar Now Playing rises out of is tinted for a problem, so the sheet starts from `errorContainer`. Pure. */
internal fun sheetStartsTinted(state: PlayerUiState.Loaded): Boolean =
    (MiniPlayerLines.of(state) as? MiniPlayerLine.Problem)?.isTinted == true
