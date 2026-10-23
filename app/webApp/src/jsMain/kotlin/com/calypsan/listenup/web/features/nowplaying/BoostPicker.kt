package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.web.design.ChipOption
import com.calypsan.listenup.web.design.ChipRadioGroup
import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.player.isSameVolumeBoost
import com.calypsan.listenup.domain.VolumeBoostLimits
import org.jetbrains.compose.web.dom.Button as DomButton
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
    placement: PanelPlacement,
    onDismiss: () -> Unit,
) {
    PlayerPanel(
        open = open,
        title = NowPlayingWords.VOLUME_BOOST,
        panelClass = "boost-dlg",
        placement = placement,
        onDismiss = onDismiss,
    ) {
        P(attrs = {
            classes("boost-read")
            attr("role", "status")
            attr("aria-live", "off")
        }) {
            Text(formatBoost(boostDb))
        }

        // Radios in one connected row (`Sheets-WebPhone`): which boost is in force is a choice of one, and a screen
        // reader says so. Picking one leaves the panel open (PB5), so the listener hears the difference.
        ChipRadioGroup(
            label = NowPlayingWords.BOOST_LEVEL,
            options = VolumeBoostLimits.PRESETS_DB.map { ChipOption(it, boostLevel(it)) },
            selected = boostDb,
            onSelect = onSet,
            styleClass = "boost-opts",
            same = ::isSameVolumeBoost,
            optionClass = "boost-opt",
        )

        P(attrs = { classes("panel-note") }) { Text(NowPlayingWords.BOOST_NOTE) }

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

        // Only when there is something to go back to, exactly as the speed picker does: a reset
        // that is already at its target is a control whose press changes nothing.
        if (!isSameVolumeBoost(boostDb, defaultBoostDb)) {
            DomButton(attrs = {
                classes("panel-reset", "boost-reset")
                attr("type", "button")
                onClick { onReset() }
            }) {
                Text(NowPlayingWords.resetBoost(formatBoost(defaultBoostDb)))
            }
        }
    }
}

/** A level as its radio reads it (the board): "Off", "+3", "+6". The readout above says the unit. */
internal fun boostLevel(db: Float): String {
    val rounded = db.roundToInt()
    return if (rounded <= VolumeBoostLimits.MIN_DB) "Off" else "+$rounded"
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
