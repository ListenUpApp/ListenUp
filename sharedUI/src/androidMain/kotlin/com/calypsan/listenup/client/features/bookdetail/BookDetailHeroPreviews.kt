package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.features.bookdetail.components.CompactHero

/**
 * Design previews for [CompactHero] at a phone width, in light and dark, against the static
 * fallback palette so the designed coral scheme renders rather than a Material You sample.
 */
private const val PHONE_WIDTH = 412
private const val PHONE_HEIGHT = 800

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
