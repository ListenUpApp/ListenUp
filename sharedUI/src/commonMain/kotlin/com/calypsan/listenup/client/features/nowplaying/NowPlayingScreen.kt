package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.util.PlatformPredictiveBackHandler
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Duration

// Predictive back gesture animation: scale shrinks 10%, alpha fades 50% at full progress
private const val PREDICTIVE_BACK_SCALE_REDUCTION = 0.1f
private const val PREDICTIVE_BACK_ALPHA_REDUCTION = 0.5f

// Drag-to-dismiss: release past a third of the screen height collapses the player.
private const val DRAG_DISMISS_FRACTION = 0.33f

// TV ambient mode: fade controls out after this idle period.
private const val TV_AMBIENT_DELAY_MS = 15_000L

/**
 * Full screen Now Playing view.
 *
 * Hosts the screen-level chrome — predictive-back dismissal, drag-to-dismiss, and TV ambient
 * fade — then dispatches the body to an adaptive layout: [WideNowPlaying] on expanded-width
 * viewports (large tablets, desktop, TV) and [CompactNowPlaying] everywhere else.
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
fun NowPlayingScreen(
    state: NowPlayingState.Active,
    progress: () -> PlaybackProgress,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSpeedClick: () -> Unit,
    onChaptersClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onStorySoFarClick: () -> Unit,
    onGoToBook: () -> Unit,
    onGoToSeries: (String) -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onCloseBook: () -> Unit,
    hasPdf: Boolean = false,
    onOpenPdf: () -> Unit = {},
    isTv: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Predictive back: track gesture progress to animate dismissal (scale + alpha)
    val backProgress = remember { Animatable(0f) }
    // Gate the handler until the screen has fully entered composition. The composable is
    // reachable during the AnimatedVisibility enter-transition, so enabling immediately would
    // let a back gesture fire before the screen is presented, creating a jarring mid-slide
    // dismiss. Flipping `presented` on the first composition frame is the lightest safe guard.
    var presented by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { presented = true }
    PlatformPredictiveBackHandler(enabled = presented) { progressFlow ->
        try {
            progressFlow.collect { progress -> backProgress.snapTo(progress) }
            onCollapse()
        } catch (cancellation: CancellationException) {
            // Gesture abandoned — rewind the dismissal animation. On commit the
            // screen is already exiting, so progress is intentionally left as-is
            // to avoid a scale/alpha pop mid exit-transition.
            backProgress.snapTo(0f)
            throw cancellation
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
    val currentChapterTitle = state.chapterTitle
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
        animationSpec = tween(durationMillis = 1000),
        label = "ambientAlpha",
    )

    // Drag-to-dismiss state
    val scope = rememberCoroutineScope()
    val screenHeightPx =
        LocalWindowInfo.current.containerSize.height
            .toFloat()
    val dismissThreshold = screenHeightPx * DRAG_DISMISS_FRACTION

    val dragOffset = remember { Animatable(0f) }

    // When a predictive-back gesture begins, immediately clear any in-flight drag offset so the
    // two transforms (translationY from drag + scale/alpha from back) never compound. snapTo is
    // intentional — an animated clear would itself compound with the back animation.
    LaunchedEffect(backProgress.value != 0f) {
        if (backProgress.value != 0f && dragOffset.value != 0f) dragOffset.snapTo(0f)
    }

    val expanded =
        currentWindowAdaptiveInfo()
            .windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    Surface(
        modifier =
            modifier
                .fillMaxSize()
                .onKeyEvent {
                    if (isTv) resetAmbient()
                    false // don't consume
                }.graphicsLayer {
                    translationY = dragOffset.value
                    val backScale = 1f - backProgress.value * PREDICTIVE_BACK_SCALE_REDUCTION
                    scaleX = backScale
                    scaleY = backScale
                    // Ambient fade (TV idle) multiplies into the predictive-back fade. With no TV
                    // ambient the factor is 1f, so this is a no-op on phone/tablet.
                    alpha = ambientAlpha * (1f - backProgress.value * PREDICTIVE_BACK_ALPHA_REDUCTION)
                }.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (dragOffset.value > dismissThreshold) {
                                    // Animate off screen then collapse
                                    dragOffset.animateTo(
                                        targetValue = screenHeightPx,
                                        animationSpec = tween(200),
                                    )
                                    onCollapse()
                                } else {
                                    // Snap back to open
                                    dragOffset.animateTo(
                                        targetValue = 0f,
                                        animationSpec = tween(200),
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                dragOffset.animateTo(0f, tween(200))
                            }
                        },
                        onVerticalDrag = { _, dragAmount ->
                            scope.launch {
                                // Only allow dragging down (positive values)
                                val newOffset = (dragOffset.value + dragAmount).coerceAtLeast(0f)
                                dragOffset.snapTo(newOffset)
                            }
                        },
                    )
                },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (expanded) {
                WideNowPlaying(
                    state = state,
                    progress = progress,
                    onCollapse = onCollapse,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    onSpeedClick = onSpeedClick,
                    onSleepClick = onSleepTimerClick,
                    onChaptersClick = onChaptersClick,
                    onStorySoFarClick = onStorySoFarClick,
                    onGoToBook = onGoToBook,
                    onGoToSeries = onGoToSeries,
                    onGoToContributor = onGoToContributor,
                    onShowAuthorPicker = onShowAuthorPicker,
                    onShowNarratorPicker = onShowNarratorPicker,
                    onCloseBook = onCloseBook,
                    hasPdf = hasPdf,
                    onOpenPdf = onOpenPdf,
                )
            } else {
                CompactNowPlaying(
                    state = state,
                    progress = progress,
                    onCollapse = onCollapse,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    onSpeedClick = onSpeedClick,
                    onSleepClick = onSleepTimerClick,
                    onChaptersClick = onChaptersClick,
                    onStorySoFarClick = onStorySoFarClick,
                    onGoToBook = onGoToBook,
                    onGoToSeries = onGoToSeries,
                    onGoToContributor = onGoToContributor,
                    onShowAuthorPicker = onShowAuthorPicker,
                    onShowNarratorPicker = onShowNarratorPicker,
                    onCloseBook = onCloseBook,
                    hasPdf = hasPdf,
                    onOpenPdf = onOpenPdf,
                )
            }
        }
    }
}

// Extension function for formatting playback time
fun Duration.formatPlaybackTime(): String {
    val hours = inWholeHours
    val minutes = inWholeMinutes % 60
    val seconds = inWholeSeconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
