package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import kotlin.math.roundToInt

/**
 * M3 Expressive wavy progress bar with seek functionality.
 *
 * Combines LinearWavyProgressIndicator with drag gesture handling
 * and a thumb indicator for seeking.
 *
 * The wave animation reflects playback state:
 * - When playing: wave builds from flat at start to full amplitude at playhead
 * - When paused: completely flat/still
 *
 * @param progress Current progress value (0f to 1f)
 * @param onSeek Called when user finishes seeking with new progress value
 * @param modifier Modifier for the composable
 * @param isPlaying Whether audio is currently playing (animates wave when true)
 * @param enabled Whether seeking is enabled
 * @param color The color of the filled track (defaults to primary)
 * @param trackColor The color of the unfilled track (defaults to surfaceVariant)
 * @param stateDescription Human-readable description of the current position for TalkBack,
 *   e.g. "3:30 of 42:00". Provide this from the call site where Duration values are available.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WavySeekBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    stateDescription: String = "",
) {
    val density = LocalDensity.current
    val haptics = LocalHaptics.current

    // Track width for calculating drag position
    var trackWidth by remember { mutableFloatStateOf(0f) }

    // Track dragging state
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(progress) }

    // Use drag progress while dragging, otherwise use actual progress
    val displayProgress = if (isDragging) dragProgress else progress

    // Thumb size
    val thumbSize = 20.dp
    val thumbSizePx = with(density) { thumbSize.toPx() }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(48.dp) // Touch target height
                .seekBarSemantics(
                    progress = progress,
                    stateDescription = stateDescription,
                    enabled = enabled,
                    onSeek = onSeek,
                ).onSizeChanged { size ->
                    trackWidth = size.width.toFloat()
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput

                    detectTapGestures { offset ->
                        // Calculate progress from tap position
                        val newProgress = (offset.x / trackWidth).coerceIn(0f, 1f)
                        onSeek(newProgress)
                    }
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput

                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragProgress = (offset.x / trackWidth).coerceIn(0f, 1f)
                            haptics.selectionTick()
                        },
                        onDragEnd = {
                            if (isDragging) {
                                onSeek(dragProgress)
                                isDragging = false
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            if (trackWidth > 0) {
                                dragProgress = (dragProgress + dragAmount / trackWidth).coerceIn(0f, 1f)
                            }
                        },
                    )
                },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Wavy progress indicator track
        LinearWavyProgressIndicator(
            progress = { displayProgress },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .align(Alignment.Center),
            color = color,
            trackColor = trackColor,
            amplitude = { if (isPlaying) 1f else 0f },
            wavelength = 24.dp,
            waveSpeed = 15.dp,
        )

        // Thumb indicator
        Box(
            modifier =
                Modifier
                    .offset {
                        val thumbOffset = (displayProgress * (trackWidth - thumbSizePx)).roundToInt()
                        IntOffset(thumbOffset, 0)
                    }.size(thumbSize)
                    .shadow(
                        elevation = if (isDragging) 8.dp else 4.dp,
                        shape = CircleShape,
                    ).clip(CircleShape)
                    .background(color),
        )
    }
}

/**
 * Attaches screen-reader semantics to the seek bar:
 * - [ProgressBarRangeInfo] exposes the current position; TalkBack uses this to announce
 *   the widget class as a seekbar and read the current value aloud.
 * - [stateDescription] surfaces the human-readable "3:30 of 42:00" string supplied by
 *   the call site (which has Duration context).
 * - [setProgress] lets TalkBack and Switch Access move the thumb programmatically.
 */
private fun Modifier.seekBarSemantics(
    progress: Float,
    stateDescription: String,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
): Modifier =
    semantics {
        progressBarRangeInfo =
            ProgressBarRangeInfo(
                current = progress.coerceIn(0f, 1f),
                range = 0f..1f,
            )
        if (stateDescription.isNotEmpty()) {
            this.stateDescription = stateDescription
        }
        if (enabled) {
            setProgress(label = "Seek") { targetValue ->
                onSeek(targetValue.coerceIn(0f, 1f))
                true
            }
        }
    }
