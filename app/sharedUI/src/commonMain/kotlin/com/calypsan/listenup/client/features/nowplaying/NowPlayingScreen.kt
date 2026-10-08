package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import com.calypsan.listenup.client.design.motion.PredictiveBackEdgeMargin
import com.calypsan.listenup.client.design.motion.predictiveBackPreview
import com.calypsan.listenup.client.design.util.BackGestureEdge
import com.calypsan.listenup.client.design.util.PlatformPredictiveBackHandler
import com.calypsan.listenup.client.foldable.LocalFold
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackProgress
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration

// Drag-to-dismiss: release past a third of the screen height collapses the player.
private const val DRAG_DISMISS_FRACTION = 0.33f

// TV ambient mode: fade controls out after this idle period.
private const val TV_AMBIENT_DELAY_MS = 15_000L

/**
 * Full screen Now Playing view.
 *
 * Hosts the screen-level chrome — predictive-back dismissal, drag-to-dismiss, and TV ambient
 * fade — then dispatches the body to an adaptive layout ([nowPlayingLayout]): [TabletopNowPlaying]
 * split at the hinge on a foldable half open on a table, [WideNowPlaying] on wide or short windows
 * and in book posture, and [CompactNowPlaying] everywhere else.
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
    onBoostClick: () -> Unit,
    onChaptersClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onGoToBook: () -> Unit,
    onGoToSeries: (String) -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onCloseBook: () -> Unit,
    skipBackwardSec: Int,
    skipForwardSec: Int,
    hasPdf: Boolean = false,
    onOpenPdf: () -> Unit = {},
    isTv: Boolean = false,
    modifier: Modifier = Modifier,
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
        // Bespoke: a deliberately slow, even dim into TV ambient mode, like a sleep fade.
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
    val motion = MaterialTheme.motionScheme

    // When a predictive-back gesture begins, immediately clear any in-flight drag offset so the
    // two transforms (translationY from drag + the back preview) never compound. snapTo is
    // intentional — an animated clear would itself compound with the back animation.
    LaunchedEffect(backProgress.value != 0f) {
        if (backProgress.value != 0f && dragOffset.value != 0f) dragOffset.snapTo(0f)
    }

    // The corners the sheet rounds toward as the back preview shrinks it off the window's edges.
    val backPreviewCorner = MaterialTheme.shapes.extraLarge.topStart
    val fold = LocalFold.current
    val layout = nowPlayingLayout(currentWindowAdaptiveInfoV2().windowSizeClass, fold)

    Surface(
        modifier =
            modifier
                .fillMaxSize()
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
                    translationY = dragOffset.value
                    scaleX = preview.scale
                    scaleY = preview.scale
                    shape = RoundedCornerShape(backPreviewCorner.toPx(size, this) * preview.cornerFraction)
                    clip = preview.cornerFraction > 0f
                    // Ambient fade (TV idle) only; the back preview stays opaque, so the page beneath
                    // never shows through the player. With no TV ambient this is 1f.
                    alpha = ambientAlpha * preview.alpha
                }.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (dragOffset.value > dismissThreshold) {
                                    // Animate off screen then collapse
                                    dragOffset.animateTo(
                                        targetValue = screenHeightPx,
                                        // Bespoke: a fixed, quick flight so the collapse is not held
                                        // back by a spring settling off screen.
                                        animationSpec = tween(200),
                                    )
                                    onCollapse()
                                } else {
                                    // Snap back to open
                                    dragOffset.animateTo(
                                        targetValue = 0f,
                                        animationSpec = motion.fastSpatialSpec(),
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                dragOffset.animateTo(0f, motion.fastSpatialSpec())
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
        when (layout) {
            NowPlayingLayout.Tabletop -> {
                TabletopNowPlaying(
                    state = state,
                    progress = progress,
                    // nowPlayingLayout only answers Tabletop when the fold has bounds.
                    hingeBounds = requireNotNull(fold.hingeBounds),
                    onCollapse = onCollapse,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    onSpeedClick = onSpeedClick,
                    onBoostClick = onBoostClick,
                    onSleepClick = onSleepTimerClick,
                    onChaptersClick = onChaptersClick,
                    onGoToBook = onGoToBook,
                    onGoToSeries = onGoToSeries,
                    onGoToContributor = onGoToContributor,
                    onShowAuthorPicker = onShowAuthorPicker,
                    onShowNarratorPicker = onShowNarratorPicker,
                    onCloseBook = onCloseBook,
                    skipBackwardSec = skipBackwardSec,
                    skipForwardSec = skipForwardSec,
                    hasPdf = hasPdf,
                    onOpenPdf = onOpenPdf,
                )
            }

            NowPlayingLayout.SideBySide -> {
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
                    onBoostClick = onBoostClick,
                    onSleepClick = onSleepTimerClick,
                    onChaptersClick = onChaptersClick,
                    onGoToBook = onGoToBook,
                    onGoToSeries = onGoToSeries,
                    onGoToContributor = onGoToContributor,
                    onShowAuthorPicker = onShowAuthorPicker,
                    onShowNarratorPicker = onShowNarratorPicker,
                    onCloseBook = onCloseBook,
                    skipBackwardSec = skipBackwardSec,
                    skipForwardSec = skipForwardSec,
                    hasPdf = hasPdf,
                    onOpenPdf = onOpenPdf,
                )
            }

            NowPlayingLayout.Stacked -> {
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
                    onBoostClick = onBoostClick,
                    onSleepClick = onSleepTimerClick,
                    onChaptersClick = onChaptersClick,
                    onGoToBook = onGoToBook,
                    onGoToSeries = onGoToSeries,
                    onGoToContributor = onGoToContributor,
                    onShowAuthorPicker = onShowAuthorPicker,
                    onShowNarratorPicker = onShowNarratorPicker,
                    onCloseBook = onCloseBook,
                    skipBackwardSec = skipBackwardSec,
                    skipForwardSec = skipForwardSec,
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

    // ASCII digits in every locale, like DurationFormatter, so one screen never mixes two numeral
    // systems; and plain padding rather than String.format, which commonMain cannot reach.
    val clock = "$minutes:${seconds.toString().padStart(2, '0')}"
    return if (hours >
        0
    ) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        clock
    }
}
