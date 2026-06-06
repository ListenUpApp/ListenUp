package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ElevatedCoverCard
import com.calypsan.listenup.client.design.util.PlatformPredictiveBackHandler
import com.calypsan.listenup.client.features.settings.PlaybackSpeedPresets
import com.calypsan.listenup.client.foldable.LocalPosture
import com.calypsan.listenup.client.foldable.Posture
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Duration

// Predictive back gesture animation: scale shrinks 10%, alpha fades 50% at full progress
private const val PREDICTIVE_BACK_SCALE_REDUCTION = 0.1f
private const val PREDICTIVE_BACK_ALPHA_REDUCTION = 0.5f

// Tabletop posture splits Now Playing into two equal halves above/below the hinge.
private const val TABLETOP_HALF_WEIGHT = 0.5f

// Drag-to-dismiss: release past a third of the screen height collapses the player.
private const val DRAG_DISMISS_FRACTION = 0.33f

// TV ambient mode: fade controls out after this idle period.
private const val TV_AMBIENT_DELAY_MS = 15_000L

/**
 * Full screen Now Playing view.
 *
 * M3 Expressive styling:
 * - Diverse button shapes (large play, medium skip, small chapter)
 * - Chapter-scoped seek bar
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
fun NowPlayingScreen(
    state: NowPlayingState.Active,
    sleepTimerState: SleepTimerState,
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
    onGoToBook: () -> Unit,
    onGoToSeries: (String) -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onCloseBook: () -> Unit,
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

    // Breathing animation for cover art
    val breathTransition = rememberInfiniteTransition(label = "breath")
    val breathScale by breathTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 7000, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "breathScale",
    )

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
                    alpha = 1f - backProgress.value * PREDICTIVE_BACK_ALPHA_REDUCTION
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
        // Adaptive layout based on available space
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val constraintsWidth = maxWidth
            val constraintsHeight = maxHeight
            val isWideLayout = constraintsWidth > constraintsHeight

            Box(modifier = Modifier.fillMaxSize()) {
                if (isWideLayout) {
                    // Wide layout: cover on left, controls on right
                    WideNowPlayingLayout(
                        state = state,
                        sleepTimerState = sleepTimerState,
                        breathScale = breathScale,
                        ambientAlpha = ambientAlpha,
                        onCollapse = {
                            resetAmbient()
                            onCollapse()
                        },
                        onPlayPause = {
                            resetAmbient()
                            onPlayPause()
                        },
                        onSeek = {
                            resetAmbient()
                            onSeek(it)
                        },
                        onSkipBack = {
                            resetAmbient()
                            onSkipBack()
                        },
                        onSkipForward = {
                            resetAmbient()
                            onSkipForward()
                        },
                        onPreviousChapter = {
                            resetAmbient()
                            onPreviousChapter()
                        },
                        onNextChapter = {
                            resetAmbient()
                            onNextChapter()
                        },
                        onSpeedClick = {
                            resetAmbient()
                            onSpeedClick()
                        },
                        onChaptersClick = {
                            resetAmbient()
                            onChaptersClick()
                        },
                        onSleepTimerClick = {
                            resetAmbient()
                            onSleepTimerClick()
                        },
                        onGoToBook = {
                            resetAmbient()
                            onGoToBook()
                        },
                        onGoToSeries = {
                            resetAmbient()
                            onGoToSeries(it)
                        },
                        onGoToContributor = {
                            resetAmbient()
                            onGoToContributor(it)
                        },
                        onShowAuthorPicker = {
                            resetAmbient()
                            onShowAuthorPicker()
                        },
                        onShowNarratorPicker = {
                            resetAmbient()
                            onShowNarratorPicker()
                        },
                        onCloseBook = {
                            resetAmbient()
                            onCloseBook()
                        },
                    )
                } else {
                    // Tall layout: original vertical layout
                    TallNowPlayingLayout(
                        state = state,
                        sleepTimerState = sleepTimerState,
                        breathScale = breathScale,
                        ambientAlpha = ambientAlpha,
                        onCollapse = {
                            resetAmbient()
                            onCollapse()
                        },
                        onPlayPause = {
                            resetAmbient()
                            onPlayPause()
                        },
                        onSeek = onSeek,
                        onSkipBack = onSkipBack,
                        onSkipForward = onSkipForward,
                        onPreviousChapter = onPreviousChapter,
                        onNextChapter = onNextChapter,
                        onSpeedClick = onSpeedClick,
                        onChaptersClick = onChaptersClick,
                        onSleepTimerClick = onSleepTimerClick,
                        onGoToBook = onGoToBook,
                        onGoToSeries = onGoToSeries,
                        onGoToContributor = onGoToContributor,
                        onShowAuthorPicker = onShowAuthorPicker,
                        onShowNarratorPicker = onShowNarratorPicker,
                        onCloseBook = {
                            resetAmbient()
                            onCloseBook()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Tall (portrait) layout - original vertical arrangement.
 */
@Suppress("LongParameterList")
@Composable
private fun TallNowPlayingLayout(
    state: NowPlayingState.Active,
    sleepTimerState: SleepTimerState,
    breathScale: Float,
    ambientAlpha: Float,
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
    onGoToBook: () -> Unit,
    onGoToSeries: (String) -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onCloseBook: () -> Unit,
) {
    val posture = LocalPosture.current

    when (posture) {
        Posture.TABLETOP -> {
            TabletopNowPlayingLayout(
                state = state,
                sleepTimerState = sleepTimerState,
                breathScale = breathScale,
                ambientAlpha = ambientAlpha,
                onCollapse = onCollapse,
                onPlayPause = onPlayPause,
                onSeek = onSeek,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                onSpeedClick = onSpeedClick,
                onChaptersClick = onChaptersClick,
                onSleepTimerClick = onSleepTimerClick,
                onGoToBook = onGoToBook,
                onGoToSeries = onGoToSeries,
                onGoToContributor = onGoToContributor,
                onShowAuthorPicker = onShowAuthorPicker,
                onShowNarratorPicker = onShowNarratorPicker,
                onCloseBook = onCloseBook,
            )
        }

        Posture.NORMAL, Posture.BOOK -> {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp),
            ) {
                // Top bar with collapse handle and menu
                Box(modifier = Modifier.graphicsLayer { alpha = ambientAlpha }) {
                    NowPlayingTopBar(
                        state = state,
                        onCollapse = onCollapse,
                        onGoToBook = onGoToBook,
                        onGoToSeries = onGoToSeries,
                        onGoToContributor = onGoToContributor,
                        onShowAuthorPicker = onShowAuthorPicker,
                        onShowNarratorPicker = onShowNarratorPicker,
                        onCloseBook = onCloseBook,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Cover art
                Box(
                    modifier =
                        Modifier
                            .weight(0.45f)
                            .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CoverArt(
                        bookId = state.bookId,
                        coverPath = state.coverPath,
                        coverBlurHash = state.coverBlurHash,
                        title = state.title,
                        breathScale = breathScale,
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Title and chapter info
                Box(modifier = Modifier.graphicsLayer { alpha = ambientAlpha }) {
                    TitleSection(
                        title = state.title,
                        author = state.author,
                        chapterTitle = state.chapterTitle,
                        chapterLabel = state.chapterLabel,
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Chapter seek bar
                Box(modifier = Modifier.graphicsLayer { alpha = ambientAlpha }) {
                    ChapterSeekBar(
                        progress = state.chapterProgress,
                        currentTime = state.chapterPosition,
                        totalTime = state.chapterDuration,
                        isPlaying = state.isPlaying,
                        onSeek = onSeek,
                    )
                }

                Spacer(Modifier.height(32.dp))

                // Main controls
                Box(modifier = Modifier.graphicsLayer { alpha = ambientAlpha }) {
                    MainControls(
                        isPlaying = state.isPlaying,
                        isBuffering = state.isBuffering,
                        onPlayPause = onPlayPause,
                        onSkipBack = onSkipBack,
                        onSkipForward = onSkipForward,
                        onPreviousChapter = onPreviousChapter,
                        onNextChapter = onNextChapter,
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Secondary controls
                Box(modifier = Modifier.graphicsLayer { alpha = ambientAlpha }) {
                    SecondaryControls(
                        playbackSpeed = state.playbackSpeed,
                        sleepTimerState = sleepTimerState,
                        onSpeedClick = onSpeedClick,
                        onChaptersClick = onChaptersClick,
                        onSleepTimerClick = onSleepTimerClick,
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Tabletop layout — device half-open with horizontal hinge.
 *
 * Splits the screen at the hinge: cover art occupies the upper half,
 * playback controls the lower half, mirroring the physical table stance.
 */
@Suppress("LongParameterList")
@Composable
private fun TabletopNowPlayingLayout(
    state: NowPlayingState.Active,
    sleepTimerState: SleepTimerState,
    breathScale: Float,
    ambientAlpha: Float,
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
    onGoToBook: () -> Unit,
    onGoToSeries: (String) -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onCloseBook: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
    ) {
        // Upper half: cover art anchored above the hinge
        Box(
            modifier =
                Modifier
                    .weight(TABLETOP_HALF_WEIGHT)
                    .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            CoverArt(
                bookId = state.bookId,
                coverPath = state.coverPath,
                coverBlurHash = state.coverBlurHash,
                title = state.title,
                breathScale = breathScale,
            )
        }

        // Lower half: controls below the hinge
        Column(
            modifier =
                Modifier
                    .weight(TABLETOP_HALF_WEIGHT)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Box(modifier = Modifier.graphicsLayer { alpha = ambientAlpha }) {
                NowPlayingTopBar(
                    state = state,
                    onCollapse = onCollapse,
                    onGoToBook = onGoToBook,
                    onGoToSeries = onGoToSeries,
                    onGoToContributor = onGoToContributor,
                    onShowAuthorPicker = onShowAuthorPicker,
                    onShowNarratorPicker = onShowNarratorPicker,
                    onCloseBook = onCloseBook,
                )
            }

            Box(modifier = Modifier.graphicsLayer { alpha = ambientAlpha }) {
                TitleSection(
                    title = state.title,
                    author = state.author,
                    chapterTitle = state.chapterTitle,
                    chapterLabel = state.chapterLabel,
                )
            }

            Box(modifier = Modifier.graphicsLayer { alpha = ambientAlpha }) {
                ChapterSeekBar(
                    progress = state.chapterProgress,
                    currentTime = state.chapterPosition,
                    totalTime = state.chapterDuration,
                    isPlaying = state.isPlaying,
                    onSeek = onSeek,
                )
            }

            Box(modifier = Modifier.graphicsLayer { alpha = ambientAlpha }) {
                MainControls(
                    isPlaying = state.isPlaying,
                    isBuffering = state.isBuffering,
                    onPlayPause = onPlayPause,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                )
            }

            Box(modifier = Modifier.graphicsLayer { alpha = ambientAlpha }) {
                SecondaryControls(
                    playbackSpeed = state.playbackSpeed,
                    sleepTimerState = sleepTimerState,
                    onSpeedClick = onSpeedClick,
                    onChaptersClick = onChaptersClick,
                    onSleepTimerClick = onSleepTimerClick,
                )
            }
        }
    }
}

/**
 * Wide (landscape/tablet) layout - cover on left, controls on right.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun WideNowPlayingLayout(
    state: NowPlayingState.Active,
    sleepTimerState: SleepTimerState,
    breathScale: Float,
    ambientAlpha: Float,
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
    onGoToBook: () -> Unit,
    onGoToSeries: (String) -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onCloseBook: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        // Left side: Cover art (takes up ~40% of width)
        Box(
            modifier =
                Modifier
                    .weight(0.4f)
                    .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            CoverArt(
                bookId = state.bookId,
                coverPath = state.coverPath,
                coverBlurHash = state.coverBlurHash,
                title = state.title,
                breathScale = breathScale,
            )
        }

        // Right side: Controls and info
        Column(
            modifier =
                Modifier
                    .weight(0.6f)
                    .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top bar with collapse and menu
            Box(modifier = Modifier.graphicsLayer { alpha = ambientAlpha }) {
                NowPlayingTopBar(
                    state = state,
                    onCollapse = onCollapse,
                    onGoToBook = onGoToBook,
                    onGoToSeries = onGoToSeries,
                    onGoToContributor = onGoToContributor,
                    onShowAuthorPicker = onShowAuthorPicker,
                    onShowNarratorPicker = onShowNarratorPicker,
                    onCloseBook = onCloseBook,
                )
            }

            // Title and chapter info (left-aligned for wide layout)
            Box(modifier = Modifier.graphicsLayer { alpha = ambientAlpha }) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = state.author,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    state.chapterTitle?.let { chapterTitle ->
                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = chapterTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        Text(
                            text = state.chapterLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Chapter seek bar
            Box(modifier = Modifier.graphicsLayer { alpha = ambientAlpha }) {
                ChapterSeekBar(
                    progress = state.chapterProgress,
                    currentTime = state.chapterPosition,
                    totalTime = state.chapterDuration,
                    isPlaying = state.isPlaying,
                    onSeek = onSeek,
                )
            }

            // Main controls
            Box(modifier = Modifier.graphicsLayer { alpha = ambientAlpha }) {
                MainControls(
                    isPlaying = state.isPlaying,
                    isBuffering = state.isBuffering,
                    onPlayPause = onPlayPause,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                )
            }

            // Secondary controls
            Box(modifier = Modifier.graphicsLayer { alpha = ambientAlpha }) {
                SecondaryControls(
                    playbackSpeed = state.playbackSpeed,
                    sleepTimerState = sleepTimerState,
                    onSpeedClick = onSpeedClick,
                    onChaptersClick = onChaptersClick,
                    onSleepTimerClick = onSleepTimerClick,
                )
            }
        }
    }
}

@Suppress("CognitiveComplexMethod")
@Composable
private fun NowPlayingTopBar(
    state: NowPlayingState.Active,
    onCollapse: () -> Unit,
    onGoToBook: () -> Unit,
    onGoToSeries: (String) -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onCloseBook: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Drag handle indicator
        Surface(
            modifier =
                Modifier
                    .width(40.dp)
                    .height(4.dp),
            shape = RoundedCornerShape(2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        ) {}

        // Collapse button
        IconButton(
            onClick = onCollapse,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Collapse",
            )
        }

        // Overflow menu
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More options",
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                // Go to Book
                DropdownMenuItem(
                    text = { Text("Go to Book") },
                    onClick = {
                        showMenu = false
                        onGoToBook()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Book, contentDescription = null)
                    },
                )

                // Go to Series (if available)
                if (state.seriesId != null) {
                    DropdownMenuItem(
                        text = { Text("Go to Series") },
                        onClick = {
                            showMenu = false
                            state.seriesId?.let { onGoToSeries(it) }
                        },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null)
                        },
                    )
                }

                // Go to Author(s)
                if (state.authors.isNotEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(if (state.hasMultipleAuthors) "Go to Author..." else "Go to Author")
                        },
                        onClick = {
                            showMenu = false
                            if (state.hasMultipleAuthors) {
                                onShowAuthorPicker()
                            } else {
                                state.authors.firstOrNull()?.let { onGoToContributor(it.id) }
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null)
                        },
                    )
                }

                // Go to Narrator(s)
                if (state.narrators.isNotEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(if (state.hasMultipleNarrators) "Go to Narrator..." else "Go to Narrator")
                        },
                        onClick = {
                            showMenu = false
                            if (state.hasMultipleNarrators) {
                                onShowNarratorPicker()
                            } else {
                                state.narrators.firstOrNull()?.let { onGoToContributor(it.id) }
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                        },
                    )
                }

                HorizontalDivider()

                // Close Book
                DropdownMenuItem(
                    text = { Text("Close Book") },
                    onClick = {
                        showMenu = false
                        onCloseBook()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Close, contentDescription = null)
                    },
                )
            }
        }
    }
}

/**
 * Square cover art with shadow and a gentle breathing animation.
 *
 * Loads via the shared [ElevatedCoverCard] (local-file fast path with authenticated server-URL
 * fallback and a BlurHash placeholder), the same mechanism Book Detail uses.
 */
@Composable
private fun CoverArt(
    bookId: String,
    coverPath: String?,
    coverBlurHash: String?,
    title: String,
    breathScale: Float = 1f,
) {
    ElevatedCoverCard(
        path = coverPath,
        bookId = bookId,
        blurHash = coverBlurHash,
        contentDescription = title,
        cornerRadius = 16.dp,
        elevation = 24.dp,
        modifier =
            Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .graphicsLayer {
                    scaleX = breathScale
                    scaleY = breathScale
                },
    )
}

@Composable
private fun TitleSection(
    title: String,
    author: String,
    chapterTitle: String?,
    chapterLabel: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = author,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (chapterTitle != null) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = chapterTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = chapterLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Chapter seek bar using M3 Expressive wavy progress indicator.
 *
 * Uses WavySeekBar for a more expressive, audiobook-appropriate UI
 * that clearly indicates this is a progress bar with seek capability.
 */
@Composable
private fun ChapterSeekBar(
    progress: Float,
    currentTime: Duration,
    totalTime: Duration,
    isPlaying: Boolean,
    onSeek: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // M3 Expressive wavy seek bar
        WavySeekBar(
            progress = progress,
            onSeek = onSeek,
            modifier = Modifier.fillMaxWidth(),
            isPlaying = isPlaying,
            stateDescription = "${currentTime.formatPlaybackTime()} of ${totalTime.formatPlaybackTime()}",
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = currentTime.formatPlaybackTime(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = totalTime.formatPlaybackTime(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * M3 Expressive media controls following ButtonGroup pattern.
 *
 * Key principles:
 * - UNIFORM height (all buttons 56dp)
 * - DIVERSE shape (leading/trailing rounded, middle squared)
 * - DIVERSE color (play button filled, others tonal)
 * - Connected layout (minimal 4dp gaps, flows as unit)
 * - Play button slightly wider via weight
 */
@Composable
private fun MainControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    // Shape definitions matching M3 Expressive connected button group
    val buttonHeight = 56.dp
    val cornerRadius = 16.dp
    val fullRadius = 28.dp // For pill ends

    // Leading shape: left side fully rounded, right side squared
    val leadingShape =
        RoundedCornerShape(
            topStart = fullRadius,
            bottomStart = fullRadius,
            topEnd = cornerRadius,
            bottomEnd = cornerRadius,
        )

    // Middle shape: all corners slightly rounded (squircle)
    val middleShape = RoundedCornerShape(cornerRadius)

    // Trailing shape: right side fully rounded, left side squared
    val trailingShape =
        RoundedCornerShape(
            topStart = cornerRadius,
            bottomStart = cornerRadius,
            topEnd = fullRadius,
            bottomEnd = fullRadius,
        )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Previous chapter - LEADING shape (left pill end)
        FilledTonalButton(
            onClick = onPreviousChapter,
            modifier =
                Modifier
                    .weight(1f)
                    .height(buttonHeight),
            shape = leadingShape,
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = "Previous chapter",
                modifier = Modifier.size(24.dp),
            )
        }

        // Skip back 10s - MIDDLE shape
        FilledTonalButton(
            onClick = onSkipBack,
            modifier =
                Modifier
                    .weight(1f)
                    .height(buttonHeight),
            shape = middleShape,
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(
                Icons.Default.Replay10,
                contentDescription = "Skip back 10 seconds",
                modifier = Modifier.size(24.dp),
            )
        }

        // Play/Pause - MIDDLE shape but FILLED color (hero) and wider
        // Shows a spinner while the player is buffering so the user knows it's loading.
        Button(
            onClick = onPlayPause,
            enabled = !isBuffering,
            modifier =
                Modifier
                    .weight(1.4f)
                    .height(buttonHeight),
            shape = middleShape,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            contentPadding = PaddingValues(0.dp),
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        // Skip forward 30s - MIDDLE shape
        FilledTonalButton(
            onClick = onSkipForward,
            modifier =
                Modifier
                    .weight(1f)
                    .height(buttonHeight),
            shape = middleShape,
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(
                Icons.Default.Forward30,
                contentDescription = "Skip forward 30 seconds",
                modifier = Modifier.size(24.dp),
            )
        }

        // Next chapter - TRAILING shape (right pill end)
        FilledTonalButton(
            onClick = onNextChapter,
            modifier =
                Modifier
                    .weight(1f)
                    .height(buttonHeight),
            shape = trailingShape,
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "Next chapter",
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun SecondaryControls(
    playbackSpeed: Float,
    sleepTimerState: SleepTimerState,
    onSpeedClick: () -> Unit,
    onChaptersClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Speed button - opens speed picker sheet
        TextButton(onClick = onSpeedClick) {
            Text(
                text = PlaybackSpeedPresets.format(playbackSpeed),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // Chapters button
        TextButton(onClick = onChaptersClick) {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Chapters",
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // Sleep timer button
        SleepTimerButton(
            timerState = sleepTimerState,
            onClick = onSleepTimerClick,
        )
    }
}

/**
 * Sleep button that shows active timer state.
 */
@Composable
private fun SleepTimerButton(
    timerState: SleepTimerState,
    onClick: () -> Unit,
) {
    val isActive = timerState is SleepTimerState.Active
    val isFading = timerState is SleepTimerState.FadingOut

    val buttonText =
        when (timerState) {
            is SleepTimerState.Inactive -> {
                "Sleep"
            }

            is SleepTimerState.Active -> {
                when (timerState.mode) {
                    is SleepTimerMode.Duration -> timerState.formatRemaining()
                    is SleepTimerMode.EndOfChapter -> "End of ch."
                }
            }

            is SleepTimerState.FadingOut -> {
                "..."
            }
        }

    val contentColor =
        when {
            isActive -> MaterialTheme.colorScheme.primary
            isFading -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.onSurface
        }

    TextButton(
        onClick = onClick,
        enabled = !isFading,
    ) {
        Icon(
            Icons.Default.Bedtime,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = contentColor,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = buttonText,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
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
