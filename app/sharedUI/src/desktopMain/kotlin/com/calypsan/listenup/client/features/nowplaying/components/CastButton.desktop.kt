package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun CastButton(modifier: Modifier) {
    // Cast is Android-only; render nothing on Desktop.
}
