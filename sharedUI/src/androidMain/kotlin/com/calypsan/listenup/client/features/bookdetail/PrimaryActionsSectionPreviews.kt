package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.features.bookdetail.components.PrimaryActionsSection

// LocalDeviceContext defaults to DeviceContext(DeviceType.Phone), which has
// supportsDownloads = true, so both buttons render without any ambient override.

@Composable
private fun PreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    ListenUpTheme(darkTheme = dark, dynamicColor = false, content = content)
}

@Composable
private fun PrimaryActionsSectionPreviewBody(
    playEnabled: Boolean = true,
    showServerWarning: Boolean = false,
) {
    PrimaryActionsSection(
        downloadStatus = BookDownloadStatus.NotDownloaded(bookId = "preview-book"),
        onPlayClick = {},
        onDownloadClick = {},
        onCancelClick = {},
        onDeleteClick = {},
        modifier = Modifier.padding(horizontal = 16.dp),
        playEnabled = playEnabled,
        showServerWarning = showServerWarning,
    )
}

@Preview(name = "PrimaryActions · light · enabled", widthDp = 412, heightDp = 100)
@Composable
private fun PrimaryActionsLightEnabled() {
    PreviewTheme(dark = false) { PrimaryActionsSectionPreviewBody(playEnabled = true) }
}

@Preview(name = "PrimaryActions · dark · enabled", widthDp = 412, heightDp = 100)
@Composable
private fun PrimaryActionsDarkEnabled() {
    PreviewTheme(dark = true) { PrimaryActionsSectionPreviewBody(playEnabled = true) }
}

@Preview(name = "PrimaryActions · light · disabled", widthDp = 412, heightDp = 100)
@Composable
private fun PrimaryActionsLightDisabled() {
    PreviewTheme(dark = false) { PrimaryActionsSectionPreviewBody(playEnabled = false) }
}

@Preview(name = "PrimaryActions · dark · disabled", widthDp = 412, heightDp = 100)
@Composable
private fun PrimaryActionsDarkDisabled() {
    PreviewTheme(dark = true) { PrimaryActionsSectionPreviewBody(playEnabled = false) }
}

@Preview(name = "PrimaryActions · light · offline morph", widthDp = 412, heightDp = 100)
@Composable
private fun PrimaryActionsLightOffline() {
    PreviewTheme(dark = false) {
        PrimaryActionsSectionPreviewBody(playEnabled = false, showServerWarning = true)
    }
}

@Preview(name = "PrimaryActions · dark · offline morph", widthDp = 412, heightDp = 100)
@Composable
private fun PrimaryActionsDarkOffline() {
    PreviewTheme(dark = true) {
        PrimaryActionsSectionPreviewBody(playEnabled = false, showServerWarning = true)
    }
}
