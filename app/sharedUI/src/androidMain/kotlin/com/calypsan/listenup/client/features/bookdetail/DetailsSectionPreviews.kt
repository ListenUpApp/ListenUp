package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.features.bookdetail.components.DetailsSection

private val PREVIEW_CREDITS =
    listOf(
        BookContributor(id = "c1", name = "Brandon Sanderson", roles = listOf("author")),
        BookContributor(id = "c2", name = "Michael Kramer", roles = listOf("narrator")),
        BookContributor(id = "c3", name = "Kate Reading", roles = listOf("narrator")),
        BookContributor(id = "c4", name = "Natalia Sylvester", roles = listOf("translator")),
        BookContributor(id = "c5", name = "Peter Ahlstrom", roles = listOf("editor")),
        BookContributor(id = "c6", name = "Bryce Moore", roles = listOf("foreword by")),
    )

private val PREVIEW_AUDIO_FILES =
    listOf(
        AudioFile(
            id = "1",
            index = 0,
            filename = "01.m4b",
            format = "m4b",
            codec = "ac4",
            duration = 3_600_000L,
            size = 1_000_000L,
            spatial = "atmos",
            bitrate = 320_000,
            sampleRate = 48_000,
            channels = 6,
        ),
    )

@Composable
private fun PreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    ListenUpTheme(darkTheme = dark, dynamicColor = false, content = content)
}

// Same-role contributors group into one row (the two narrators share a "Narrators" row).

@Preview(name = "DetailsSection · light", widthDp = 412, heightDp = 600)
@Composable
private fun DetailsSectionLight() {
    PreviewTheme(dark = false) {
        DetailsSection(
            publisher = "Tor Books",
            publishYear = 2010,
            language = "en",
            audioFiles = PREVIEW_AUDIO_FILES,
            credits = PREVIEW_CREDITS,
            onContributorClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "DetailsSection · dark", widthDp = 412, heightDp = 600)
@Composable
private fun DetailsSectionDark() {
    PreviewTheme(dark = true) {
        DetailsSection(
            publisher = "Tor Books",
            publishYear = 2010,
            language = "en",
            audioFiles = PREVIEW_AUDIO_FILES,
            credits = PREVIEW_CREDITS,
            onContributorClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
