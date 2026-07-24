package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.features.bookdetail.components.OfflineBanner

@Composable
private fun PreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    ListenUpTheme(darkTheme = dark, dynamicColor = false, content = content)
}

@Preview(name = "OfflineBanner · wide · light", widthDp = 412, heightDp = 100)
@Composable
private fun OfflineBannerWideLightPreview() {
    PreviewTheme(dark = false) {
        OfflineBanner(
            onRetryClick = {},
            modifier = Modifier.padding(horizontal = 16.dp),
            compact = false,
        )
    }
}

@Preview(name = "OfflineBanner · wide · dark", widthDp = 412, heightDp = 100)
@Composable
private fun OfflineBannerWideDarkPreview() {
    PreviewTheme(dark = true) {
        OfflineBanner(
            onRetryClick = {},
            modifier = Modifier.padding(horizontal = 16.dp),
            compact = false,
        )
    }
}

@Preview(name = "OfflineBanner · compact · light", widthDp = 412, heightDp = 80)
@Composable
private fun OfflineBannerCompactLightPreview() {
    PreviewTheme(dark = false) {
        OfflineBanner(
            onRetryClick = {},
            modifier = Modifier.padding(horizontal = 16.dp),
            compact = true,
        )
    }
}

@Preview(name = "OfflineBanner · compact · dark", widthDp = 412, heightDp = 80)
@Composable
private fun OfflineBannerCompactDarkPreview() {
    PreviewTheme(dark = true) {
        OfflineBanner(
            onRetryClick = {},
            modifier = Modifier.padding(horizontal = 16.dp),
            compact = true,
        )
    }
}
