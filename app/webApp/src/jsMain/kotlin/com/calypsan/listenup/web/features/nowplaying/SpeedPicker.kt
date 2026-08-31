package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.nowplaying.PLAYBACK_SPEED_INCREMENT
import com.calypsan.listenup.client.presentation.nowplaying.PLAYBACK_SPEED_MAX
import com.calypsan.listenup.client.presentation.nowplaying.PLAYBACK_SPEED_MIN
import com.calypsan.listenup.client.presentation.nowplaying.PLAYBACK_SPEED_STEPS
import com.calypsan.listenup.client.presentation.nowplaying.isSamePlaybackSpeed
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import kotlin.math.roundToInt

/**
 * Choose a reading pace, either by naming one or by feeling for it.
 *
 * Replaces the cycle the speed control used to be. The cycle was defensible while it was all web
 * had — a listener nudging the pace up one rung wants one tap, not a menu — but it made every
 * other move expensive: the ladder is nine rungs, so dropping from 1× to 0.75× meant eight taps
 * past 3×, and there was no way to reach a rate between rungs at all.
 *
 * Both halves are here for that reason, and they are not redundant. The chips are the rungs people
 * actually name ("put it on 1.5"), reachable in one tap. The slider is for the pace nobody has a
 * name for — the one that is right for this narrator, three hundredths above a preset. Native's
 * `PlaybackSpeedSheet` offers exactly this pair; matching it means a listener who found their rate
 * on a phone is not told the browser has no such setting.
 *
 * The ladder, the bounds and the increment all come from commonMain — [PLAYBACK_SPEED_STEPS],
 * [PLAYBACK_SPEED_MIN], [PLAYBACK_SPEED_MAX], [PLAYBACK_SPEED_INCREMENT] — rather than being
 * restated here, so the browser cannot offer a different set of rates, a different range or a
 * finer adjustment than the phone does.
 */
@Composable
internal fun SpeedPicker(
    open: Boolean,
    speed: Float,
    defaultSpeed: Float,
    onSet: (Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Seeded from `speed` and re-seeded whenever it changes, so the readout tracks the drag at
    // pointer speed rather than waiting for the player to report back. Committing on every `input`
    // would push a rate change into the decoder per pointer sample.
    var dragSpeed by remember(speed) { mutableStateOf(speed) }

    PlayerDialog(
        open = open,
        title = "Playback speed",
        panelClass = "speed-dlg",
        onDismiss = onDismiss,
    ) {
        P(attrs = {
            classes("mono", "speed-read")
            attr("role", "status")
            attr("aria-live", "off")
        }) {
            Text("${formatSpeed(dragSpeed)}×")
        }

        Input(type = InputType.Range) {
            classes("speed-slide")
            attr("min", hundredths(PLAYBACK_SPEED_MIN).toString())
            attr("max", hundredths(PLAYBACK_SPEED_MAX).toString())
            // Hundredths, because a range's value is a number and 0.05 of a step would round to
            // nothing. Five of them is the same 0.05 increment the native slider snaps to.
            attr("step", STEP_HUNDREDTHS.toString())
            attr("aria-label", "Playback speed")
            // The implicit `aria-valuenow` is a raw hundredths count — "one hundred and twenty
            // five" is not a speed anyone recognises.
            attr("aria-valuetext", "${formatSpeed(dragSpeed)} times")
            value(hundredths(dragSpeed).toString())
            onInput { event -> parsedSpeed(event.target.value)?.let { dragSpeed = it } }
            onChange { event -> parsedSpeed(event.target.value)?.let { onSet(it) } }
        }

        Div(attrs = { classes("speed-opts") }) {
            PLAYBACK_SPEED_STEPS.forEach { preset ->
                val isCurrent = isSamePlaybackSpeed(preset, speed)
                Button(attrs = {
                    classes("speed-opt")
                    if (isCurrent) classes("on")
                    attr("type", "button")
                    // `aria-current`, not just a class: which rate is playing is information, and
                    // a colour alone says nothing to a listener who cannot see it.
                    if (isCurrent) attr("aria-current", "true")
                    onClick {
                        dragSpeed = preset
                        onSet(preset)
                    }
                }) {
                    Text("${formatSpeed(preset)}×")
                }
            }
        }

        // Only when there is something to go back to. A reset button while already at the default
        // is a control whose press changes nothing — and it is the listener's own default from
        // Settings, not a hardcoded 1×, so this offers to undo a choice rather than to overrule one.
        if (!isSamePlaybackSpeed(speed, defaultSpeed)) {
            Button(attrs = {
                classes("btn-ghost", "speed-reset")
                attr("type", "button")
                onClick {
                    dragSpeed = defaultSpeed
                    onReset()
                }
            }) {
                Text("Reset to ${formatSpeed(defaultSpeed)}×")
            }
        }
    }
}

private fun hundredths(speed: Float): Int = (speed * HUNDREDTHS_PER_UNIT).roundToInt()

/** A range element's `value` as a speed, or null when it is not one. */
private fun parsedSpeed(raw: String): Float? =
    raw
        .toDoubleOrNull()
        ?.takeIf { it.isFinite() }
        ?.let { (it / HUNDREDTHS_PER_UNIT).toFloat() }
        ?.takeIf { it >= PLAYBACK_SPEED_MIN && it <= PLAYBACK_SPEED_MAX }

private const val HUNDREDTHS_PER_UNIT = 100

/**
 * The slider's own step, in the hundredths its `value` is carried in.
 *
 * Derived from [PLAYBACK_SPEED_INCREMENT] rather than written as `5`, so the browser cannot end up
 * offering a finer or coarser adjustment than the phone does.
 */
private val STEP_HUNDREDTHS = (PLAYBACK_SPEED_INCREMENT * HUNDREDTHS_PER_UNIT).roundToInt()
