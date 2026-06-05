package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.features.bookdetail.components.CompactHero
import com.calypsan.listenup.client.features.bookdetail.components.WideHeroBand

/**
 * Design previews for [CompactHero] and [WideHeroBand], in light and dark, against the static
 * fallback palette so the designed coral scheme renders rather than a Material You sample.
 */
private const val PHONE_WIDTH = 412
private const val PHONE_HEIGHT = 800
private const val WIDE_WIDTH = 1000
private const val WIDE_HEIGHT = 400

@Composable
private fun PreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    ListenUpTheme(darkTheme = dark, dynamicColor = false, content = content)
}

@Composable
private fun CompactHeroPreviewBody() {
    CompactHero(
        coverPath = null,
        bookId = "preview-book",
        title = "Game of Thrones",
        overline = "Epic Fantasy · Unabridged",
        subtitle = "A Song of Ice and Fire · Book One",
        authorLine = "George R.R. Martin",
        narratorLine = "Narrated by Roy Dotrice",
        progress = 0.4f,
        timeRemaining = "21h 30m left",
    )
}

@Preview(name = "CompactHero · light", widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT)
@Composable
private fun CompactHeroLight() {
    PreviewTheme(dark = false) { CompactHeroPreviewBody() }
}

@Preview(name = "CompactHero · dark", widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT)
@Composable
private fun CompactHeroDark() {
    PreviewTheme(dark = true) { CompactHeroPreviewBody() }
}

@Composable
private fun WideHeroBandPreviewBody() {
    WideHeroBand(
        coverPath = null,
        bookId = "preview-book",
        title = "Game of Thrones",
        overline = "Epic Fantasy · Unabridged",
        subtitle = "A Song of Ice and Fire · Book One",
        authorLine = "George R.R. Martin",
        narratorLine = "Narrated by Roy Dotrice",
        progress = 0.4f,
        timeRemaining = "21h 30m left",
    )
}

@Preview(name = "WideHeroBand · light", widthDp = WIDE_WIDTH, heightDp = WIDE_HEIGHT)
@Composable
private fun WideHeroBandLight() {
    PreviewTheme(dark = false) { WideHeroBandPreviewBody() }
}

@Preview(name = "WideHeroBand · dark", widthDp = WIDE_WIDTH, heightDp = WIDE_HEIGHT)
@Composable
private fun WideHeroBandDark() {
    PreviewTheme(dark = true) { WideHeroBandPreviewBody() }
}
