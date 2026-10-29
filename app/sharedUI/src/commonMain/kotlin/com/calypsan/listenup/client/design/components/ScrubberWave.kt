package com.calypsan.listenup.client.design.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.motion.rememberAmbientLoop
import kotlin.math.PI
import kotlin.math.sin

/** The track's thickness, active and inactive alike; the active one waves about its centre line. */
internal val ScrubberTrackThickness = 4.dp

/** The wave's crest height either side of the centre line, at full amplitude. */
internal val ScrubberWaveAmplitudeHeight = 3.dp

/** One crest to the next (the deleted `WavySeekBar`'s `wavelength = 24.dp`). */
internal val ScrubberWavelength = 24.dp

/** The scrubber's handle width (M3 Expressive's 4dp handle). */
internal val ScrubberHandleWidth = 4.dp

/** M3's gap either side of the handle (`SliderTokens.ActiveHandleLeadingSpace`). */
internal val ScrubberHandleGap = 6.dp

/** One wavelength's travel: 24dp at the deleted `WavySeekBar`'s `waveSpeed = 15.dp` per second. */
private const val WAVE_TRAVEL_MILLIS = 1_600

/** Enough points per wavelength that the sampled line reads as a curve at any density. */
private const val WAVE_SAMPLES_PER_WAVELENGTH = 16

/**
 * Where the wave is along its travel, 0 to 1 of a wavelength, for the mini bar, the morph's flying line and Now
 * Playing's scrubber to share, so the one wave never jumps as it passes between them.
 *
 * Read [current] only while drawing: it reads the travel loop, so a frame redraws and nothing recomposes. While no loop
 * runs (paused) it holds the last phase it gave, so a wave easing flat stops where it was instead of snapping back.
 */
@Stable
internal class ScrubberWavePhase {
    internal var loop: State<Float>? by mutableStateOf(null)
    private var last = 0f

    /** The phase now; draw-time only. */
    fun current(): Float {
        val running = loop ?: return last
        return running.value.also { last = it }
    }
}

/**
 * The phase the player's waves share, for design components that cannot see the morph: Now Playing's stage provides
 * the morph's [ScrubberWavePhase] to its scrubber. Null where nothing provides one (the toolbar, desktop, previews), and
 * each wave then keeps its own.
 */
internal val LocalScrubberWavePhase = staticCompositionLocalOf<ScrubberWavePhase?> { null }

/** A [ScrubberWavePhase] of a wave's own, travelling only while [travels] ([DriveScrubberWavePhase]). */
@Composable
internal fun rememberScrubberWavePhase(travels: Boolean): ScrubberWavePhase {
    val phase = remember { ScrubberWavePhase() }
    DriveScrubberWavePhase(phase, travels)
    return phase
}

/**
 * Runs [phase]'s travel loop while [travels], at the deleted `WavySeekBar`'s speed, and stops it otherwise (asking for
 * no frames); held still while animations are removed, so a wave stays but does not move.
 */
@Composable
internal fun DriveScrubberWavePhase(
    phase: ScrubberWavePhase,
    travels: Boolean,
) {
    val loop =
        if (travels) {
            rememberAmbientLoop(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(WAVE_TRAVEL_MILLIS, easing = LinearEasing)),
                restValue = 0f,
                label = "scrubber-wave-phase",
            )
        } else {
            null
        }
    SideEffect { phase.loop = loop }
}

/** The provided shared phase, or one of this wave's own that travels while [travels]. */
@Composable
internal fun rememberSharedOrOwnWavePhase(travels: Boolean): ScrubberWavePhase =
    LocalScrubberWavePhase.current ?: rememberScrubberWavePhase(travels)

/**
 * M3 Expressive's determinate amplitude rule (`WavyProgressIndicatorDefaults.indicatorAmplitude`): flat at or below
 * 10 % and at or above 95 % of [fraction], full between. Every wave of the player follows it. Pure.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun scrubberWaveAmplitude(fraction: Float): Float = WavyProgressIndicatorDefaults.indicatorAmplitude(fraction)

/**
 * Where the scrubber's parts sit along a slider [width] px wide, in its own x px, as M3's `Slider` lays them out: the
 * track inset half the handle at each end, the handle's centre at [fraction] along the track, and the wave and the
 * inactive track each stopping a handle-half plus the gap short of it. [handleShown] (0..1) eases the inset and the
 * gaps in, so the morph's line can grow them as its handle fades in; at 1 this is the slider exactly. Mirrored in
 * right-to-left layouts ([isRtl]): the track then runs from the right. Pure.
 */
internal fun scrubberTrackGeometry(
    width: Float,
    fraction: Float,
    handleWidth: Float,
    handleGap: Float,
    handleShown: Float = 1f,
    isRtl: Boolean = false,
): ScrubberTrackGeometry {
    val inset = handleWidth / 2f * handleShown
    val clearance = (handleWidth / 2f + handleGap) * handleShown
    val trackWidth = width - 2f * inset
    val handleCentre = inset + trackWidth * fraction.coerceIn(0f, 1f)
    val ltr =
        ScrubberTrackGeometry(
            trackStart = inset,
            waveEnd = handleCentre - clearance,
            handleCentre = handleCentre,
            inactiveStart = handleCentre + clearance,
            trackEnd = width - inset,
        )
    return if (isRtl) ltr.mirrored(width) else ltr
}

/**
 * The scrubber's parts along its x axis, px from the slider's left edge: the track from [trackStart] to [trackEnd]; the
 * wave from [trackStart] to [waveEnd]; the handle at [handleCentre]; the inactive track from [inactiveStart] to
 * [trackEnd]. In right-to-left layouts each start lies to the right of its end.
 */
internal data class ScrubberTrackGeometry(
    val trackStart: Float,
    val waveEnd: Float,
    val handleCentre: Float,
    val inactiveStart: Float,
    val trackEnd: Float,
) {
    /** These parts measured from the right edge of a [width] px slider. */
    fun mirrored(width: Float): ScrubberTrackGeometry =
        ScrubberTrackGeometry(
            trackStart = width - trackStart,
            waveEnd = width - waveEnd,
            handleCentre = width - handleCentre,
            inactiveStart = width - inactiveStart,
            trackEnd = width - trackEnd,
        )

    /** These parts moved [dx] px along x. */
    fun offsetBy(dx: Float): ScrubberTrackGeometry =
        ScrubberTrackGeometry(
            trackStart = trackStart + dx,
            waveEnd = waveEnd + dx,
            handleCentre = handleCentre + dx,
            inactiveStart = inactiveStart + dx,
            trackEnd = trackEnd + dx,
        )
}

/**
 * Draws the scrubber's active track: a [ScrubberTrackThickness] line from x [from] to x [to] (either way round) on
 * [centreY], waving at [amplitude] (0 flat to 1 full) and shifted [phase] of a wavelength towards [to]. Round caps sit
 * inside both ends, so a flat wave is M3's rounded bar. Draws nothing shorter than its own cap. Reuses [path] so a frame
 * allocates nothing.
 */
internal fun DrawScope.drawScrubberWave(
    path: Path,
    from: Float,
    to: Float,
    amplitude: Float,
    phase: Float,
    color: Color,
    centreY: Float = center.y,
) {
    val stroke = ScrubberTrackThickness.toPx()
    val cap = stroke / 2f
    val length = kotlin.math.abs(to - from)
    if (length <= cap) return
    val direction = if (to >= from) 1f else -1f
    val crest = ScrubberWaveAmplitudeHeight.toPx() * amplitude
    val wavelength = ScrubberWavelength.toPx()
    val step = wavelength / WAVE_SAMPLES_PER_WAVELENGTH

    fun pointAt(distance: Float) =
        Offset(
            from + direction * distance,
            centreY + crest * sin(2f * PI.toFloat() * (distance / wavelength - phase)),
        )

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
