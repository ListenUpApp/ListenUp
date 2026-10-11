package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.features.bookdetail.components.BookDetailHeroBand
import com.calypsan.listenup.client.features.bookdetail.components.ListenActionGroup
import com.calypsan.listenup.client.features.bookdetail.components.ResumeUnit
import com.calypsan.listenup.client.features.bookdetail.components.WideHeroBand
import com.calypsan.listenup.client.presentation.bookdetail.BookSeriesPath
import com.calypsan.listenup.client.presentation.bookdetail.CurrentChapter
import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesCrumb

/**
 * Design previews for [BookDetailHeroBand] and [WideHeroBand], in light and dark, against the static
 * fallback palette so the designed coral scheme renders rather than a Material You sample.
 */
private const val PHONE_WIDTH = 412
private const val PHONE_HEIGHT = 800
private const val WIDE_WIDTH = 1000
private const val WIDE_HEIGHT = 560

private const val PREVIEW_TITLE = "Mistborn"
private const val PREVIEW_SUBTITLE = "The Final Empire"
private const val PREVIEW_BOOK_ID = "preview-book"
private const val PREVIEW_PROGRESS = 0.4f
private const val PREVIEW_DURATION_MS = 24L * 3600 * 1000 + 41L * 60 * 1000
private const val PREVIEW_YEAR = 2006
private const val PREVIEW_DOWNLOAD_BYTES = 1_288_490_189L

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

// Two series lines + an independent subtitle: a deep path that folds its middle, and a short one.
private val previewSeries =
    listOf(
        BookSeriesPath(
            seriesId = "s1",
            seriesName = "Mistborn Era 1",
            sequence = "1",
            ancestors =
                listOf(
                    SeriesCrumb("s0", "The Cosmere"),
                    SeriesCrumb("s2", "Scadrial"),
                    SeriesCrumb("s3", "Mistborn"),
                ),
        ),
        // Fractional on purpose: a regression that renders "#3.0" would show here.
        BookSeriesPath(seriesId = "s4", seriesName = "Secret Projects", sequence = "3.5", ancestors = emptyList()),
    )

@Composable
private fun CompactHeroPreviewBody() {
    BookDetailHeroBand(
        coverPath = null,
        coverHash = null,
        bookId = PREVIEW_BOOK_ID,
        title = PREVIEW_TITLE,
        subtitle = PREVIEW_SUBTITLE,
        seriesPaths = previewSeries,
        authors = previewAuthors,
        narrators = previewNarrators,
        durationMs = PREVIEW_DURATION_MS,
        year = PREVIEW_YEAR,
        abridged = false,
        onContributorClick = {},
        onSeriesClick = {},
        onShowCast = {},
    )
}

@Preview(name = "BookDetailHeroBand · light", widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT)
@Composable
private fun CompactHeroLight() {
    PreviewTheme(dark = false) { CompactHeroPreviewBody() }
}

@Preview(name = "BookDetailHeroBand · dark", widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT)
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
        subtitle = PREVIEW_SUBTITLE,
        seriesPaths = previewSeries,
        authors = previewAuthors,
        narrators = previewNarrators,
        durationMs = PREVIEW_DURATION_MS,
        year = PREVIEW_YEAR,
        abridged = false,
        onContributorClick = {},
        onSeriesClick = {},
        onShowCast = {},
        resumeUnit = {
            ResumeUnit(
                readingStage = ReadingStage.InProgress,
                currentChapter = CurrentChapter(index = 5, number = 6, title = "The Well of Ascension", startMs = 0, elapsedInChapterMs = 0),
                timeRemainingMs = PREVIEW_DURATION_MS * 6 / 10,
                progress = PREVIEW_PROGRESS,
                finishedSpan = null,
            )
        },
        actions = {
            ListenActionGroup(
                primaryAction = PrimaryAction.Resume,
                isPreparing = false,
                downloadStatus = BookDownloadStatus.NotDownloaded(PREVIEW_BOOK_ID),
                isWaitingForWifi = false,
                downloadSizeBytes = PREVIEW_DOWNLOAD_BYTES,
                supportsDownloads = true,
                canDownload = true,
                labelled = true,
                onPrimaryClick = {},
                onDownload = {},
                onCancelDownload = {},
                onRemoveDownload = {},
                onAddToShelf = {},
            )
        },
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
