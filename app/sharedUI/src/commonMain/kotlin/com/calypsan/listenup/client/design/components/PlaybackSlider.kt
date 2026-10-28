package com.calypsan.listenup.client.design.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.motion.rememberAmbientLoop
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_scrubber_a11y
import listenup.composeapp.generated.resources.player_scrubber_value_a11y
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The track's thickness, active and inactive alike; the active one waves about its centre line. */
internal val ScrubberTrackThickness = 4.dp

/** The wave's crest height either side of the centre line, at full amplitude. */
internal val ScrubberWaveAmplitudeHeight = 3.dp

/** One crest to the next (the deleted `WavySeekBar`'s `wavelength = 24.dp`). */
internal val ScrubberWavelength = 24.dp

/** One wavelength's travel: 24dp at the deleted `WavySeekBar`'s `waveSpeed = 15.dp` per second. */
private const val WAVE_TRAVEL_MILLIS = 1_600

private val HandleSize = DpSize(width = 4.dp, height = 44.dp)

/** M3's gap either side of the handle (`SliderTokens.ActiveHandleLeadingSpace`); the wave stops where M3's bar did. */
private val HandleTrackGap = 6.dp

/** The wave's amplitude, 0 (flat) to 1 (full), as the slider states it for tests: the wave is drawn, not composed. */
internal val ScrubberWaveAmplitude = SemanticsPropertyKey<Float>("ScrubberWaveAmplitude")

/**
 * The player's scrubber (spec §6): the M3 Expressive slider whose 4dp `primary` active track is M3 Expressive's
 * wave, a 4dp handle with gaps, a flat 4dp `surfaceContainerHighest` inactive track and its stop dot, over
 * elapsed · −remaining. The wave travels while [isPlaying], and its amplitude eases to flat (and still) when paused
 * or while the handle is held, so the track never wobbles under a finger; with animations removed it waves in place.
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
    val amplitude by animateFloatAsState(
        targetValue = if (isPlaying && !isHeld) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "scrubber-wave-amplitude",
    )
    // The loop lives only while there is a wave to move, so a paused scrubber asks for no frames.
    val phase = if (amplitude > 0f) rememberScrubberWavePhase() else null
    val wavePath = remember { Path() }
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
                            val handleGap = HandleSize.width.toPx() / 2f + HandleTrackGap.toPx()
                            val mirror = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                            scale(scaleX = mirror, scaleY = 1f) {
                                drawScrubberWave(
                                    path = wavePath,
                                    length = size.width * state.coercedValueAsFraction - handleGap,
                                    amplitude = amplitude,
                                    phase = phase?.value ?: 0f,
                                    color = waveColor,
                                )
                            }
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

/**
 * The wave's travel, 0 to 1 of a wavelength, looping at the deleted `WavySeekBar`'s speed; held at 0 while
 * animations are removed, so the wave stays but does not move.
 */
@Composable
internal fun rememberScrubberWavePhase(): State<Float> =
    rememberAmbientLoop(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(WAVE_TRAVEL_MILLIS, easing = LinearEasing)),
        restValue = 0f,
        label = "scrubber-wave-phase",
    )

/**
 * Draws the scrubber's active track: a [ScrubberTrackThickness] line from the start edge, [length] px long and
 * centred on this scope's centre line, waving at [amplitude] (0 flat to 1 full) and shifted [phase] of a wavelength
 * rightwards from [startX]. Round caps, so a flat wave is M3's rounded bar. Draws nothing shorter than its own cap.
 * Reuses [path] so a frame allocates nothing. Now Playing's morph draws its flying line with this too, so the line
 * lands on the very wave it becomes.
 */
internal fun DrawScope.drawScrubberWave(
    path: Path,
    length: Float,
    amplitude: Float,
    phase: Float,
    color: Color,
    startX: Float = 0f,
    centreY: Float = center.y,
) {
    val stroke = ScrubberTrackThickness.toPx()
    val cap = stroke / 2f
    if (length <= cap) return
    val crest = ScrubberWaveAmplitudeHeight.toPx() * amplitude
    val wavelength = ScrubberWavelength.toPx()
    val step = wavelength / WAVE_SAMPLES_PER_WAVELENGTH

    fun pointAt(distance: Float) =
        Offset(startX + distance, centreY + crest * sin(2f * PI.toFloat() * (distance / wavelength - phase)))

    val end = (length - cap).coerceAtLeast(cap)
    path.reset()
    pointAt(cap).let { path.moveTo(it.x, it.y) }
    var distance = cap + step
    while (distance < end) {
        pointAt(distance).let { path.lineTo(it.x, it.y) }
        distance += step
    }
    pointAt(end).let { path.lineTo(it.x, it.y) }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Enough points per wavelength that the sampled line reads as a curve at any density. */
private const val WAVE_SAMPLES_PER_WAVELENGTH = 16

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
