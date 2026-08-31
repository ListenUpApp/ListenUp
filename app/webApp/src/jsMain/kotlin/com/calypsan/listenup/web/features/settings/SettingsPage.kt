package com.calypsan.listenup.web.features.settings

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.presentation.nowplaying.PLAYBACK_SPEED_STEPS
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import com.calypsan.listenup.web.design.CheckboxField
import com.calypsan.listenup.web.design.SelectField
import com.calypsan.listenup.web.design.SelectOption
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Settings — the preferences a browser can actually keep.
 *
 * Seven controls, not twelve. Dynamic colours are Android's wallpaper palette, Wi-Fi-only downloads
 * and volume boost need machinery web does not have, haptics need hardware, and the sleep-timer
 * default is a stored preference that no client reads yet — web has the sleep timer itself (see
 * [com.calypsan.listenup.web.features.nowplaying.SleepTimerPicker]), but nothing anywhere starts
 * one from this number, so a control here would change nothing. Each is omitted rather than shown
 * disabled: a control that cannot keep its promise is the same lie as a screen that reports success
 * it did not have, and this app has spent a lot of effort not telling that one.
 *
 * The synced/local split is real and worth saying out loud — playback defaults follow the reader to
 * their phone, appearance and library display stay on this browser — so each section says which it
 * is rather than leaving someone to discover it by changing a phone and watching nothing happen.
 */
@Composable
fun SettingsPage(
    state: SettingsUiState,
    onThemeMode: (ThemeMode) -> Unit,
    onDefaultSpeed: (Float) -> Unit,
    onSkipForward: (Int) -> Unit,
    onSkipBackward: (Int) -> Unit,
    onAutoRewind: (Boolean) -> Unit,
    onIgnoreTitleArticles: (Boolean) -> Unit,
    onHideSingleBookSeries: (Boolean) -> Unit,
    onOpenDevices: () -> Unit,
) {
    Div(attrs = { classes("set") }) {
        H1(attrs = { classes("set-title") }) { Text("Settings") }

        if (state.isLoading) {
            Div(attrs = { classes("skel", "set-skel") })
            return@Div
        }

        Section("Appearance", "Kept on this browser.") {
            SelectField(
                label = "Theme",
                value = state.themeMode.name,
                options = THEME_OPTIONS,
                onSelect = { raw -> onThemeMode(themeModeOf(raw.orEmpty())) },
            )
        }

        Section("Playback", "Follows you to your other devices.") {
            SelectField(
                label = "Default speed",
                value = speedKey(state.defaultPlaybackSpeed),
                options = SPEED_OPTIONS,
                onSelect = { raw -> raw?.toFloatOrNull()?.let(onDefaultSpeed) },
            )
            SelectField(
                label = "Skip back",
                value = state.defaultSkipBackwardSec.toString(),
                options = SKIP_OPTIONS,
                onSelect = { raw -> raw?.toIntOrNull()?.let(onSkipBackward) },
            )
            SelectField(
                label = "Skip forward",
                value = state.defaultSkipForwardSec.toString(),
                options = SKIP_OPTIONS,
                onSelect = { raw -> raw?.toIntOrNull()?.let(onSkipForward) },
            )
            CheckboxField(
                label = "Rewind a little when you come back to a book",
                checked = state.autoRewindEnabled,
                onChange = onAutoRewind,
            )
        }

        Section("Library", "Kept on this browser.") {
            CheckboxField(
                label = "Sort titles ignoring “A”, “An” and “The”",
                checked = state.ignoreTitleArticles,
                onChange = onIgnoreTitleArticles,
            )
            CheckboxField(
                label = "Hide series that contain only one book",
                checked = state.hideSingleBookSeries,
                onChange = onHideSingleBookSeries,
            )
        }

        Section("Account", null) {
            Button(attrs = {
                classes("btn-o")
                attr("type", "button")
                onClick { onOpenDevices() }
            }) { Text("Devices you are signed in on") }
        }

        Section("About", null) {
            Row("Server", state.serverUrl ?: "Not configured")
            state.serverVersion?.let { Row("Version", it) }
        }
    }
}

@Composable
private fun Section(
    heading: String,
    note: String?,
    content: @Composable () -> Unit,
) {
    Div(attrs = { classes("set-section") }) {
        H2(attrs = { classes("set-section-h") }) { Text(heading) }
        // Says where a setting lives before it is changed, not after.
        note?.let { P(attrs = { classes("set-section-note") }) { Text(it) } }
        Div(attrs = { classes("set-fields") }) { content() }
    }
}

@Composable
private fun Row(
    label: String,
    value: String,
) {
    Div(attrs = { classes("set-row") }) {
        Span(attrs = { classes("set-row-k") }) { Text(label) }
        Span(attrs = { classes("set-row-v", "mono") }) { Text(value) }
    }
}

/** The theme choices, in the order someone reasons about them: follow, then override. */
private val THEME_OPTIONS =
    listOf(
        SelectOption(ThemeMode.SYSTEM.name, "Match my system"),
        SelectOption(ThemeMode.LIGHT.name, "Light"),
        SelectOption(ThemeMode.DARK.name, "Dark"),
    )

/**
 * An unrecognised stored value falls back to following the system.
 *
 * The `<select>` can only offer what is listed above, so this is defence against a value that
 * arrived some other way — and "follow the system" is the answer least likely to surprise.
 */
internal fun themeModeOf(raw: String): ThemeMode = ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM

/** The same ladder the transport bar cycles through, so the two never disagree about what exists. */
private val SPEED_OPTIONS =
    PLAYBACK_SPEED_STEPS.map { speed -> SelectOption(speedKey(speed), "${formatSpeedLabel(speed)}×") }

private val SKIP_OPTIONS = listOf(5, 10, 15, 30, 45, 60).map { SelectOption(it.toString(), "$it seconds") }

/** A speed as its own `<option>` value — round-tripped through `toFloat`, so it must parse back. */
internal fun speedKey(speed: Float): String = speed.toString()

/** `1`, `1.5`, `1.25` — trailing zeros dropped, as on the transport bar. */
internal fun formatSpeedLabel(speed: Float): String {
    val whole = speed.toInt()
    return if (speed == whole.toFloat()) whole.toString() else speed.toString()
}
