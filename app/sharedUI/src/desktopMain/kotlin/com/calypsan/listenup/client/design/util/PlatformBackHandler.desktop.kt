package com.calypsan.listenup.client.design.util

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    // No system back button on Desktop.
    // Users exit selection mode via the close button in the SelectionToolbar.
}

@Composable
actual fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onBack: suspend (progress: Flow<Float>) -> Unit,
) {
    // Desktop has no system back gesture. No-op.
}
