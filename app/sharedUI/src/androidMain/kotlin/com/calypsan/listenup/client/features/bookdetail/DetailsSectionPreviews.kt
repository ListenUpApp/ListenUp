package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.bookdetail.components.DetailsSection
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailRow

private val PREVIEW_ROWS =
    listOf(
        BookDetailRow.Publisher("Tor Books"),
        BookDetailRow.Released(2010),
        BookDetailRow.GenreLine(genreName = "Epic fantasy", abridged = false),
        BookDetailRow.Language("English"),
        BookDetailRow.Length(durationMs = 163_800_000L, chapterCount = 75),
        BookDetailRow.Added(1_770_854_400_000L),
        BookDetailRow.Isbn("9781427209979"),
        BookDetailRow.Asin("B003ZWFO7E"),
        BookDetailRow.AllCredits(6),
        BookDetailRow.FilesAndAudio(container = "M4B", bitrate = "64 kbps"),
        BookDetailRow.MapsAndIllustrations(count = 1, onlyDocumentId = "d1", format = "PDF", totalBytes = 18_874_368L),
    )

@Composable
private fun PreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    ListenUpTheme(darkTheme = dark, dynamicColor = false, content = content)
}

@Preview(name = "DetailsSection · light", widthDp = 412, heightDp = 720)
@Composable
private fun DetailsSectionLight() {
    PreviewTheme(dark = false) {
        DetailsSection(
            rows = PREVIEW_ROWS,
            onAllCredits = {},
            onFilesAndAudio = {},
            onMaps = {},
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}

@Preview(name = "DetailsSection · dark", widthDp = 412, heightDp = 720)
@Composable
private fun DetailsSectionDark() {
    PreviewTheme(dark = true) {
        DetailsSection(
            rows = PREVIEW_ROWS,
            onAllCredits = {},
            onFilesAndAudio = {},
            onMaps = {},
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}
