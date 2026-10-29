package com.calypsan.listenup.client.design.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import com.calypsan.listenup.client.design.theme.ListenUpTheme

/**
 * The text direction this text node was laid out with: what decides where its neutral characters (a trailing comma, a
 * leading minus, the hyphen in "1-day") land. Compose resolves [TextDirection.Unspecified] to the layout's direction,
 * forcing English into a right-to-left paragraph in an Arabic locale; [TextDirection.Content] reads it from the text.
 * The style is what these specs pin, because Robolectric's text layout lays every paragraph out left to right
 * whatever direction it is given, so the drawn result cannot show the bug here (measured: a comma stays at the end).
 */
internal fun SemanticsNodeInteraction.textDirection(): TextDirection {
    val layouts = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    return layouts
        .first()
        .layoutInput.style.textDirection
}

/** [content] under the app's theme in a right-to-left layout: an Arabic or Hebrew locale with English strings. */
@Composable
internal fun RightToLeft(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ListenUpTheme(dynamicColor = false, content = content)
    }
}
