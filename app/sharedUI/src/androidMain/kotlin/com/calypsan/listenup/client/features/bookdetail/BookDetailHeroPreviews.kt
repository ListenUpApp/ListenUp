package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookSeries
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

private const val PREVIEW_TITLE = "Mistborn"
private const val PREVIEW_GENRE = "Epic Fantasy"
private const val PREVIEW_SUBTITLE = "The Final Empire"
private const val PREVIEW_BOOK_ID = "preview-book"
private const val PREVIEW_TIME_REMAINING = "21h 30m left"
private const val PREVIEW_PROGRESS = 0.4f
private const val PREVIEW_RATING = 4.8
private const val PREVIEW_DURATION_MS = 24L * 3600 * 1000 + 41L * 60 * 1000
private const val PREVIEW_YEAR = 2006
private const val PREVIEW_ADDED_AT_MS = 1_704_067_200_000L

@Composable
private fun PreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    ListenUpTheme(darkTheme = dark, dynamicColor = false, content = content)
}

private val previewAuthors = listOf(BookContributor(id = "author-1", name = "Brandon Sanderson"))
private val previewNarrators =
    listOf(
        BookContributor(id = "narrator-1", name = "Michael Kramer"),
        BookContributor(id = "narrator-2", name = "Kate Reading"),
    )

// Multiple series + an independent subtitle (the Mistborn case the design demos).
private val previewSeries =
    listOf(
        BookSeries(seriesId = "s1", seriesName = "Mistborn", sequence = "1"),
        BookSeries(seriesId = "s2", seriesName = "The Cosmere", sequence = "3"),
    )

@Composable
private fun CompactHeroPreviewBody() {
    CompactHero(
        coverPath = null,
        coverHash = null,
        bookId = PREVIEW_BOOK_ID,
        title = PREVIEW_TITLE,
        genre = PREVIEW_GENRE,
        abridged = false,
        subtitle = PREVIEW_SUBTITLE,
        series = previewSeries,
        authors = previewAuthors,
        narrators = previewNarrators,
        onContributorClick = {},
        onSeriesClick = {},
        onShowCast = {},
        progress = PREVIEW_PROGRESS,
        timeRemaining = PREVIEW_TIME_REMAINING,
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
        coverHash = null,
        bookId = PREVIEW_BOOK_ID,
        title = PREVIEW_TITLE,
        genre = PREVIEW_GENRE,
        abridged = false,
        subtitle = PREVIEW_SUBTITLE,
        series = previewSeries,
        authors = previewAuthors,
        narrators = previewNarrators,
        onContributorClick = {},
        onSeriesClick = {},
        onShowCast = {},
        progress = PREVIEW_PROGRESS,
        timeRemaining = PREVIEW_TIME_REMAINING,
        rating = PREVIEW_RATING,
        duration = PREVIEW_DURATION_MS,
        year = PREVIEW_YEAR,
        addedAt = PREVIEW_ADDED_AT_MS,
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
