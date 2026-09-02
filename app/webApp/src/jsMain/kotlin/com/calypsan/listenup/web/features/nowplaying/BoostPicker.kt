package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.nowplaying.isSameVolumeBoost
import com.calypsan.listenup.domain.VolumeBoostLimits
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import kotlin.math.roundToInt

/**
 * Turn a quiet book up.
 *
 * Chips and nothing else — no slider, unlike [SpeedPicker]. That difference is deliberate and is
 * the same on every client: a rate between rungs is a real preference someone can hear, where
 * boost is a coarse catalogue where 3 dB is about the smallest step anyone can tell apart. Offering
 * a continuous control would invite a precision the ear cannot use.
 *
 * The ladder comes from [VolumeBoostLimits.PRESETS_DB] rather than being restated here, so the
 * browser cannot offer a rung the phone does not.
 *
 * ## The one thing this panel says that the native sheets do not
 *
 * A browser can refuse to amplify — see [com.calypsan.listenup.web.playback.WebGainStage]. When it
 * does, the book keeps playing at its own volume and nothing about the page looks wrong, which is
 * exactly the shape of failure this app treats as the worst kind. So [unavailable] is said out
 * loud, here, where the listener just asked.
 */
@Composable
internal fun BoostPicker(
    open: Boolean,
    boostDb: Float,
    defaultBoostDb: Float,
    unavailable: Boolean,
    onSet: (Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerDialog(
        open = open,
        title = "Volume boost",
        panelClass = "boost-dlg",
        onDismiss = onDismiss,
    ) {
        P(attrs = {
            classes("mono", "boost-read")
            attr("role", "status")
            attr("aria-live", "off")
        }) {
            Text(formatBoost(boostDb))
        }

        if (unavailable) {
            // `role="alert"`, because it appears in response to the tap that just failed rather
            // than being on screen from the start.
            P(attrs = {
                classes("boost-warn")
                attr("role", "alert")
            }) {
                Text("This browser wouldn't let the audio be amplified. The book still plays at its own volume.")
            }
        }

        Div(attrs = { classes("boost-opts") }) {
            VolumeBoostLimits.PRESETS_DB.forEach { preset ->
                val isCurrent = isSameVolumeBoost(preset, boostDb)
                Button(attrs = {
                    classes("boost-opt")
                    if (isCurrent) classes("on")
                    attr("type", "button")
                    // Which boost is in force is information, not decoration — a coral fill says
                    // nothing to a listener who cannot see it.
                    if (isCurrent) attr("aria-current", "true")
                    onClick { onSet(preset) }
                }) {
                    Text(formatBoost(preset))
                }
            }
        }

        // Only when there is something to go back to, exactly as the speed picker does: a reset
        // that is already at its target is a control whose press changes nothing.
        if (!isSameVolumeBoost(boostDb, defaultBoostDb)) {
            Button(attrs = {
                classes("btn-ghost", "boost-reset")
                attr("type", "button")
                onClick { onReset() }
            }) {
                Text("Reset to ${formatBoost(defaultBoostDb)}")
            }
        }
    }
}

/**
 * A boost as the listener reads it: `Off` at the floor, `+6 dB` above it.
 *
 * "Off" rather than "+0 dB" because zero boost is not a setting anyone chose a number for — it is
 * the absence of one, and the native clients say the same word.
 */
internal fun formatBoost(db: Float): String {
    val rounded = db.roundToInt()
    return if (rounded <= VolumeBoostLimits.MIN_DB) "Off" else "+$rounded dB"
}
