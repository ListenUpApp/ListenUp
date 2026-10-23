package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.web.design.ChipOption
import com.calypsan.listenup.web.design.ChipRadioGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.player.PLAYBACK_SPEED_INCREMENT
import com.calypsan.listenup.client.presentation.player.PLAYBACK_SPEED_MAX
import com.calypsan.listenup.client.presentation.player.PLAYBACK_SPEED_MIN
import com.calypsan.listenup.client.presentation.player.PLAYBACK_SPEED_STEPS
import com.calypsan.listenup.client.presentation.player.isSamePlaybackSpeed
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Output
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
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
    /** The book's remainder in wall time at [speed] (`PlayerProgress.bookRemainingAtSpeedMs`, S10's one exception). */
    leftAtSpeedMs: Long,
    placement: PanelPlacement,
    onSet: (Float) -> Unit,
    /** One 0.05 step down (false) or up (true), through the shared `stepPlaybackSpeed`. */
    onStep: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Seeded from `speed` and re-seeded whenever it changes, so the readout tracks the drag at
    // pointer speed rather than waiting for the player to report back. Committing on every `input`
    // would push a rate change into the decoder per pointer sample.
    var dragSpeed by remember(speed) { mutableStateOf(speed) }

    PlayerPanel(
        open = open,
        title = PlayerSheetWords.PLAYBACK_SPEED,
        panelClass = "speed-dlg",
        placement = placement,
        onDismiss = onDismiss,
    ) {
        Div(attrs = { classes("speed-head") }) {
            Output(attrs = {
                classes("speed-read")
                attr("aria-live", "off")
            }) {
                Text(speedLabel(dragSpeed))
            }
            Span(attrs = { classes("speed-left") }) { Text(PlayerSheetWords.leftAtSpeed(leftAtSpeedMs)) }
        }

        Div(attrs = { classes("speed-fine") }) {
            StepButton(glyph = "\u2212", label = PlayerSheetWords.SLOWER) { onStep(false) }
            Input(type = InputType.Range) {
                classes("speed-slide")
                attr("min", hundredths(PLAYBACK_SPEED_MIN).toString())
                attr("max", hundredths(PLAYBACK_SPEED_MAX).toString())
                // Hundredths, because a range's value is a number and 0.05 of a step would round to
                // nothing. Five of them is the same 0.05 increment the native slider snaps to.
                attr("step", STEP_HUNDREDTHS.toString())
                attr("aria-label", PlayerSheetWords.PLAYBACK_SPEED)
                // The implicit `aria-valuenow` is a raw hundredths count — "one hundred and twenty
                // five" is not a speed anyone recognises.
                attr("aria-valuetext", "${formatSpeed(dragSpeed)} times")
                value(hundredths(dragSpeed).toString())
                onInput { event -> parsedSpeed(event.target.value)?.let { dragSpeed = it } }
                onChange { event -> parsedSpeed(event.target.value)?.let { onSet(it) } }
            }
            StepButton(glyph = "+", label = PlayerSheetWords.FASTER) { onStep(true) }
        }
        Div(attrs = {
            classes("speed-bounds")
            attr("aria-hidden", "true")
        }) {
            Span { Text(speedLabel(PLAYBACK_SPEED_MIN)) }
            Span { Text(speedLabel(PLAYBACK_SPEED_MAX)) }
        }

        // Radios, not buttons that happen to look selected: which rate is playing is a choice of one, and a screen
        // reader says so ("1.25×, radio button, checked, 4 of 9"). A rate between rungs checks none.
        ChipRadioGroup(
            label = PlayerSheetWords.SPEED_PRESETS,
            options = PLAYBACK_SPEED_STEPS.map { ChipOption(it, speedLabel(it)) },
            selected = speed,
            onSelect = { preset ->
                dragSpeed = preset
                onSet(preset)
            },
            styleClass = "speed-opts",
            same = ::isSamePlaybackSpeed,
            optionClass = "speed-opt",
        )

        // Only when there is something to go back to. A reset button while already at the default
        // is a control whose press changes nothing — and it is the listener's own default from
        // Settings, not a hardcoded 1×, so this offers to undo a choice rather than to overrule one.
        if (!isSamePlaybackSpeed(speed, defaultSpeed)) {
            Button(attrs = {
                classes("panel-reset", "speed-reset")
                attr("type", "button")
                onClick {
                    dragSpeed = defaultSpeed
                    onReset()
                }
            }) {
                Text(PlayerSheetWords.resetSpeed(speedLabel(defaultSpeed)))
            }
        }
        P(attrs = { classes("panel-note") }) { Text(PlayerSheetWords.THIS_BOOK_ONLY) }
    }
}

/** − or +: a 44px round step either side of the slider, named for what it does. */
@Composable
private fun StepButton(
    glyph: String,
    label: String,
    onClick: () -> Unit,
) {
    Button(attrs = {
        classes("speed-step")
        attr("type", "button")
        attr("aria-label", label)
        attr("title", label)
        onClick { onClick() }
    }) {
        Span(attrs = { attr("aria-hidden", "true") }) { Text(glyph) }
    }
}

/**
 * A speed as the panel writes it, with a decimal always: "1.0×", "1.25×", "3.0×" (the boards). The bar's own control is
 * terser ([formatSpeed], "1×") because it is 40px wide; the panel has the room to say it the way the presets read.
 */
internal fun speedLabel(speed: Float): String {
    val short = formatSpeed(speed)
    return if (short.contains('.')) "$short\u00D7" else "$short.0\u00D7"
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
