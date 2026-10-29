package com.calypsan.listenup.client.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_scrubber_a11y
import listenup.composeapp.generated.resources.player_scrubber_value_a11y
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val HandleSize = DpSize(width = ScrubberHandleWidth, height = 44.dp)

/** The wave's amplitude, 0 (flat) to 1 (full), as the slider states it for tests: the wave is drawn, not composed. */
internal val ScrubberWaveAmplitude = SemanticsPropertyKey<Float>("ScrubberWaveAmplitude")

/**
 * The player's scrubber (spec §6): the M3 Expressive slider whose 4dp `primary` active track is M3 Expressive's
 * wave, a 4dp handle with gaps, a flat 4dp `surfaceContainerHighest` inactive track and its stop dot, over
 * elapsed · −remaining. The wave travels while [isPlaying], and its amplitude eases to flat when paused, disabled or
 * while the handle is held, so the track never wobbles under a finger, and follows M3's rule of no wave near either
 * end ([scrubberWaveAmplitude]); with animations removed it waves in place. It travels on the player's shared phase
 * ([LocalScrubberWavePhase]) where Now Playing provides one, so the mini bar's wave and the morph's flying line land
 * on it.
 * Holds its own drag and calls [onSeek] once, on release, so a position tick never yanks the thumb and a drag never
 * issues a seek per frame (m3.material.io/components/sliders/guidelines).
 *
 * [fraction], [elapsedMs] and [durationMs] are the chapter's (§3.2: everything that moves is chapter-scoped).
 * [timesBeside] puts the times either side of the track on one row instead of under it, for a one-row toolbar.
 * [trackModifier] decorates the slider alone, not its times (Now Playing's morph measures the track).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaybackSlider(
    fraction: Float,
    elapsedMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    timesBeside: Boolean = false,
    trackModifier: Modifier = Modifier,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val shownFraction = drag ?: fraction
    val shownElapsed = drag?.let { (it * durationMs).toLong() } ?: elapsedMs
    val elapsed = shownElapsed.milliseconds.formatPlaybackTime()
    val total = durationMs.milliseconds.formatPlaybackTime()
    val remaining = "-" + (durationMs - shownElapsed).coerceAtLeast(0).milliseconds.formatPlaybackTime()
    val label = stringResource(Res.string.player_scrubber_a11y)
    val value = stringResource(Res.string.player_scrubber_value_a11y, elapsed, total)
    val colors =
        SliderDefaults.colors(
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            thumbColor = MaterialTheme.colorScheme.primary,
        )
    val waveColor = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor
    val trackColors = colors.copy(activeTrackColor = Color.Transparent, disabledActiveTrackColor = Color.Transparent)
    val isPressed by interaction.collectIsPressedAsState()
    val isDragged by interaction.collectIsDraggedAsState()
    val isHeld = drag != null || isPressed || isDragged
    val waves = enabled && isPlaying && !isHeld && scrubberWaveAmplitude(shownFraction) > 0f
    val amplitude by animateFloatAsState(
        targetValue = if (waves) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "scrubber-wave-amplitude",
    )
    val phase = rememberSharedOrOwnWavePhase(travels = amplitude > 0f)
    val wavePath = remember { Path() }
    // A clock is digits and a minus, with no letter to read a direction from: the theme reads such text left to right
    // (readingFromContent), so "-1:52" keeps its minus before the digits in an Arabic layout too.
    val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val track: @Composable (Modifier) -> Unit = { sliderModifier ->
        Slider(
            value = shownFraction,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let(onSeek)
                drag = null
            },
            enabled = enabled,
            colors = colors,
            interactionSource = interaction,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interaction,
                    colors = colors,
                    enabled = enabled,
                    thumbSize = HandleSize,
                )
            },
            track = { state ->
                // M3 draws the inactive track, its gap and its stop dot; the active track is the wave, drawn over it.
                SliderDefaults.Track(
                    sliderState = state,
                    modifier =
                        Modifier.height(ScrubberTrackThickness).drawWithContent {
                            drawContent()
                            // This canvas is M3's track, inset half the handle inside the slider; the geometry is the
                            // slider's, so shift it back by that inset.
                            val handleWidth = ScrubberHandleWidth.toPx()
                            val parts =
                                scrubberTrackGeometry(
                                    width = size.width + handleWidth,
                                    fraction = state.coercedValueAsFraction,
                                    handleWidth = handleWidth,
                                    handleGap = ScrubberHandleGap.toPx(),
                                    isRtl = layoutDirection == LayoutDirection.Rtl,
                                ).offsetBy(-handleWidth / 2f)
                            drawScrubberWave(
                                path = wavePath,
                                from = parts.trackStart,
                                to = parts.waveEnd,
                                amplitude = amplitude,
                                phase = phase.current(),
                                color = waveColor,
                            )
                        },
                    colors = trackColors,
                    enabled = enabled,
                    drawStopIndicator = { offset ->
                        with(SliderDefaults) { drawStopIndicator(offset, TrackStopIndicatorSize, waveColor) }
                    },
                )
            },
            modifier =
                trackModifier.then(sliderModifier).semantics {
                    contentDescription = label
                    stateDescription = value
                    this[ScrubberWaveAmplitude] = amplitude
                },
        )
    }
    if (timesBeside) {
        Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(elapsed, style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            track(Modifier.weight(1f))
            Text(remaining, style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(modifier.fillMaxWidth()) {
        track(Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(elapsed, style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(remaining, style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A playback position or length as a clock: "12:04", or "1:02:03" past an hour. */
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
