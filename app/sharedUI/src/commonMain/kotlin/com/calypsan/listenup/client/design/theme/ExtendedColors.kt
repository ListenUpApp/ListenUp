package com.calypsan.listenup.client.design.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The status roles Material's [androidx.compose.material3.ColorScheme] has no slot for, with
 * a light and a dark value each, so a status colour follows the theme like every other role
 * instead of being a raw hex at the call site.
 *
 * The values are fixed greens rather than harmonised with a dynamic scheme: "active" and
 * "done" must stay recognisably green whatever the wallpaper, and every pair here clears
 * WCAG AA on the fallback surfaces (pinned by `ExtendedColorsContrastTest`). [success] is also drawn as
 * text ("Up to date" on Book Detail's Hardcover card), so it clears 4.5:1 on every container a card sits on,
 * with room to spare for a dynamic scheme's slightly different surfaces.
 *
 * @property success A positive live state — an active device, a completed step. Drawn as a
 *   small mark beside a text label, never as the only cue.
 * @property onSuccess Text and icons on a [success] fill.
 * @property successContainer A quiet positive fill, such as a "done" chip.
 * @property onSuccessContainer Text and icons on a [successContainer] fill.
 */
@Immutable
data class ListenUpExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
)

internal val LightExtendedColors =
    ListenUpExtendedColors(
        success = Color(0xFF17663A),
        onSuccess = Color(0xFFFFFFFF),
        successContainer = Color(0xFFB7F1C8),
        onSuccessContainer = Color(0xFF00210F),
    )

internal val DarkExtendedColors =
    ListenUpExtendedColors(
        success = Color(0xFF7EDB9E),
        onSuccess = Color(0xFF00391D),
        successContainer = Color(0xFF00522C),
        onSuccessContainer = Color(0xFF9AF7B8),
    )

/** Provides the [ListenUpExtendedColors] for the current theme; set by [ListenUpTheme]. */
val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/** The ListenUp status roles for the current theme, alongside [MaterialTheme.colorScheme]. */
val MaterialTheme.extendedColors: ListenUpExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current
