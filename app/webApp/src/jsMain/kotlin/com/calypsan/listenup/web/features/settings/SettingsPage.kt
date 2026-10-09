package com.calypsan.listenup.web.features.settings

import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Button
import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.presentation.nowplaying.PLAYBACK_SPEED_STEPS
import com.calypsan.listenup.client.presentation.settings.HardcoverRowState
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import com.calypsan.listenup.domain.VolumeBoostLimits
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.features.nowplaying.formatBoost
import com.calypsan.listenup.web.design.CheckboxField
import com.calypsan.listenup.web.design.SelectField
import com.calypsan.listenup.web.design.SelectOption
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Settings — the preferences a browser can actually keep.
 *
 * Eight controls, not twelve. Dynamic colours are Android's wallpaper palette, Wi-Fi-only downloads
 * need machinery web does not have, haptics need hardware, and the sleep-timer default is a stored
 * preference that no client reads yet — web has the sleep timer itself (see
 * [com.calypsan.listenup.web.features.nowplaying.SleepTimerPicker]), but nothing anywhere starts
 * one from this number, so a control here would change nothing. Each is omitted rather than shown
 * disabled: a control that cannot keep its promise is the same lie as a screen that reports success
 * it did not have, and this app has spent a lot of effort not telling that one.
 *
 * Volume boost is no longer among them: `WebGainStage` amplifies through a Web Audio gain node, so
 * the number set here reaches this browser's own speakers rather than only the listener's phone.
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
    onDefaultBoost: (Float) -> Unit,
    onSkipForward: (Int) -> Unit,
    onSkipBackward: (Int) -> Unit,
    onAutoRewind: (Boolean) -> Unit,
    onIgnoreTitleArticles: (Boolean) -> Unit,
    onHideSingleBookSeries: (Boolean) -> Unit,
    onOpenDevices: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenLicences: () -> Unit = {},
    hardcoverRow: HardcoverRowState? = null,
    onOpenHardcover: () -> Unit = {},
    canCurateLibrary: Boolean = false,
    onOpenCategories: () -> Unit = {},
    onDownloadLogs: (() -> Unit)? = null,
) {
    Div(attrs = { classes("set") }) {
        PageHeader(title = "Settings")

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
            // Offered because the browser can now act on it: `WebGainStage` amplifies through a
            // Web Audio gain node, so this number reaches the speakers rather than being stored
            // for other devices to honour.
            SelectField(
                label = "Default volume boost",
                value = boostKey(state.defaultVolumeBoostDb),
                options = BOOST_OPTIONS,
                onSelect = { raw -> raw?.toFloatOrNull()?.let(onDefaultBoost) },
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

        // Categories is the server's, shared by everyone, so it leads the section — above the note
        // that says the sorting below it is kept on this browser. Only for those who may curate the
        // library: the server refuses merge and delete to anyone else.
        val categories: (@Composable () -> Unit)? =
            if (canCurateLibrary) {
                { CategoriesEntry(onOpenCategories) }
            } else {
                null
            }
        Section("Library", "Kept on this browser.", lead = categories) {
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
            Button(kind = ButtonKind.Secondary, onClick = { onOpenDevices() }) { Text("Devices you are signed in on") }
            Button(
                kind = ButtonKind.Secondary,
                onClick = { onOpenNotifications() },
            ) { Text("Which notifications reach you") }
            hardcoverRow?.let { row -> HardcoverEntry(row, onOpenHardcover) }
        }

        Section("About", null) {
            Row("App version", state.appVersion)
            Row("Server", state.serverUrl ?: "Not configured")
            state.serverVersion?.let { Row("Server version", it) }
            Button(kind = ButtonKind.Secondary, onClick = { onOpenLicences() }) { Text("Open source licenses") }
            onDownloadLogs?.let { DownloadLogsEntry(it) }
        }
    }
}

/**
 * Settings → Account → Hardcover: a title and where the connection stands, so the state is readable
 * without opening the page. Called only with a row — a null row is no entry at all.
 */
@Composable
private fun HardcoverEntry(
    row: HardcoverRowState,
    onOpen: () -> Unit,
) {
    Button(attrs = {
        classes("set-link")
        attr("type", TYPE_BUTTON)
        onClick { onOpen() }
    }) {
        Span(attrs = { classes("set-link-t") }) { Text("Hardcover") }
        Span(attrs = { classes("set-link-sub") }) { Text(hardcoverSubtitle(row)) }
    }
}

/** Settings → Library → Categories, worded as `common.categories` and `admin.categories_curate_subtitle`. */
@Composable
private fun CategoriesEntry(onOpen: () -> Unit) {
    Button(attrs = {
        classes("set-link")
        attr("type", TYPE_BUTTON)
        onClick { onOpen() }
    }) {
        Span(attrs = { classes("set-link-t") }) { Text("Categories") }
        Span(attrs = { classes("set-link-sub") }) { Text("Merge and delete genres for everyone") }
    }
}

/**
 * Settings → About → Download logs: Android's "Share logs" as a browser can offer it — the recent
 * log arrives as a file. The second line is `settings.share_logs_subtitle`, worded as every platform.
 */
@Composable
private fun DownloadLogsEntry(onDownload: () -> Unit) {
    Button(attrs = {
        classes("set-link")
        attr("type", TYPE_BUTTON)
        onClick { onDownload() }
    }) {
        Span(attrs = { classes("set-link-t") }) { Text("Download logs") }
        Span(attrs = { classes("set-link-sub") }) { Text("Export recent app logs for troubleshooting") }
    }
}

/** The entry's second line, from en.json's `hardcover.row_subtitle_*`. */
private fun hardcoverSubtitle(row: HardcoverRowState): String =
    when (row) {
        HardcoverRowState.NotConnected -> "Share what you finish with Hardcover"
        is HardcoverRowState.Connected -> "Connected as ${row.username}"
        HardcoverRowState.Connecting -> "Waiting for you to approve on Hardcover"
        HardcoverRowState.NeedsAttention -> "Needs reconnecting"
    }

@Composable
private fun Section(
    heading: String,
    note: String?,
    lead: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Div(attrs = { classes("set-section") }) {
        H2(attrs = { classes("set-section-h") }) { Text(heading) }
        // What the note does not cover goes above it, so the note only ever speaks for what follows.
        lead?.let { Div(attrs = { classes("set-fields") }) { it() } }
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

private const val TYPE_BUTTON = "button"

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

/** The same ladder the player's boost picker offers, from the contract both of them read. */
private val BOOST_OPTIONS =
    VolumeBoostLimits.PRESETS_DB.map { boost -> SelectOption(boostKey(boost), formatBoost(boost)) }

/** A boost as its own `<option>` value — round-tripped through `toFloat`, so it must parse back. */
internal fun boostKey(boostDb: Float): String = boostDb.toString()

/** A speed as its own `<option>` value — round-tripped through `toFloat`, so it must parse back. */
internal fun speedKey(speed: Float): String = speed.toString()

/** `1`, `1.5`, `1.25` — trailing zeros dropped, as on the transport bar. */
internal fun formatSpeedLabel(speed: Float): String {
    val whole = speed.toInt()
    return if (speed == whole.toFloat()) whole.toString() else speed.toString()
}
