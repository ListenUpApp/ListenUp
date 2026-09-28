package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** The one recipe for darkening artwork so text and glyphs can sit on it. */
object CoverScrimDefaults {
    /**
     * Opacity of the scrim over the artwork. 0.6 is the lowest round value that keeps
     * [ContentColor] at WCAG AA for text (4.5:1) even over a pure-white cover: white over black
     * at 0.6 on white is 5.7:1, where the 0.3–0.5 washes it replaces fell to 2–4:1.
     */
    const val ALPHA: Float = 0.6f

    /**
     * Text and glyph colour on the scrim. The M3 scrim role is black in every scheme, dynamic
     * included, so its ink is a fixed white rather than a scheme role.
     */
    val ContentColor: Color = Color.White

    /** The scrim fill for the current scheme: [MaterialTheme.colorScheme]'s scrim at [ALPHA]. */
    val color: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.scrim.copy(alpha = ALPHA)
}

/**
 * A scrim over a cover, a photo or a whole screen while it is busy: the scheme's scrim role at
 * [CoverScrimDefaults.ALPHA], with [CoverScrimDefaults.ContentColor] provided as the
 * [LocalContentColor] for whatever sits on it. Text inside reads the content colour by default;
 * a loading indicator, which defaults to primary, takes `LocalContentColor.current` explicitly.
 *
 * @param modifier Modifier for the scrim; usually `Modifier.fillMaxSize()` over its parent.
 * @param contentAlignment Where [content] sits within the scrim.
 * @param content What the scrim carries — a progress indicator, a status line.
 */
@Composable
fun CoverScrim(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier.background(CoverScrimDefaults.color),
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalContentColor provides CoverScrimDefaults.ContentColor) {
            content()
        }
    }
}
