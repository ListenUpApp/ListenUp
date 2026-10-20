package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.calypsan.listenup.client.domain.model.CoverTheme
import com.calypsan.listenup.client.domain.model.CoverThemeStyle
import com.calypsan.listenup.client.domain.model.CoverThemeTokens
import com.calypsan.listenup.client.domain.model.CoverThemes
import com.calypsan.listenup.client.domain.repository.CoverThemeRepository
import com.calypsan.listenup.core.BookId
import org.jetbrains.compose.web.attributes.AttrsScope

/**
 * The cover colour source, provided once at the shell (`WebAppRoot`) from the graph, so any hero (Book Detail today,
 * Home's slot, Now Playing next) can take its colour from a cover without threading a source through. `null` (specs,
 * the auth screens) leaves every hero neutral.
 */
val LocalCoverThemes = staticCompositionLocalOf<CoverThemeRepository?> { null }

/**
 * Web's tokens for the shared cover colour rule: Coral Fill with white in both themes (the sheet keeps the fill at
 * night: white on Lamp Coral fails), the house ink, and the paper the wash is mixed into. `CoverThemeStyleTest` holds
 * each to `00-base.css`.
 */
val WebCoverThemeStyle =
    CoverThemeStyle(
        light = CoverThemeTokens(brandArgb = 0xFFD73812.toInt(), onBrandArgb = 0xFFFFFFFF.toInt(), inkArgb = 0xFF1C1714.toInt(), paperArgb = 0xFFF5F0E8.toInt()),
        dark = CoverThemeTokens(brandArgb = 0xFFD73812.toInt(), onBrandArgb = 0xFFFFFFFF.toInt(), inkArgb = 0xFFF3EEE8.toInt(), paperArgb = 0xFF13110F.toInt()),
    )

/** A cover's colours as a hero shows them: [themes] `null` is neutral; [fadesIn] when they arrived after a cache miss. */
class CoverThemeArrival(
    val themes: CoverThemes?,
    val fadesIn: Boolean,
)

/**
 * The cover's colours for [bookId] at [coverHash]: from memory in the first composition when known (no transition), else
 * neutral until the source answers, then the colours with [CoverThemeArrival.fadesIn] (C10).
 */
@Composable
fun rememberCoverThemes(
    bookId: String,
    coverHash: String?,
): CoverThemeArrival {
    val source = LocalCoverThemes.current
    var arrival by remember(source, bookId, coverHash) {
        mutableStateOf(CoverThemeArrival(source?.cached(BookId(bookId), coverHash, WebCoverThemeStyle), fadesIn = false))
    }
    LaunchedEffect(source, bookId, coverHash) {
        if (source == null || arrival.themes != null) return@LaunchedEffect
        source.observe(BookId(bookId), coverHash, WebCoverThemeStyle).collect { arrival = CoverThemeArrival(it, fadesIn = true) }
    }
    return arrival
}

/**
 * Themes this element from its cover (C14): the `cover-themed` class and the eight custom properties `06-shell.css`
 * maps onto the hero wash, the coral fill and the progress track, light and dark; `cover-fade` when it should
 * crossfade. A neutral arrival adds nothing, so the element looks exactly as it did before this PR.
 */
fun AttrsScope<*>.coverThemed(arrival: CoverThemeArrival) {
    val themes = arrival.themes ?: return
    classes(COVER_THEMED)
    if (arrival.fadesIn) classes(COVER_FADE)
    style {
        coverVars(LIGHT, themes.light)
        coverVars(DARK, themes.dark)
    }
}

private fun org.jetbrains.compose.web.css.StyleScope.coverVars(
    suffix: String,
    theme: CoverTheme,
) {
    property("--cover-wash-$suffix", cssHex(theme.washArgb))
    property("--cover-track-$suffix", cssHex(theme.trackArgb))
    property("--cover-action-$suffix", cssHex(theme.actionArgb))
    property("--cover-on-action-$suffix", cssHex(theme.onActionArgb))
}

/** `#RRGGBB` for an ARGB colour (alpha dropped: every cover colour is opaque). */
internal fun cssHex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

private const val COVER_THEMED = "cover-themed"
private const val COVER_FADE = "cover-fade"
private const val LIGHT = "l"
private const val DARK = "d"
