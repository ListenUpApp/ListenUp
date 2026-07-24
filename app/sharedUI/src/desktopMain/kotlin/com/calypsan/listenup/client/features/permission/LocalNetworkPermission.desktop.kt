package com.calypsan.listenup.client.features.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Desktop actual for [RequestLocalNetworkPermission].
 *
 * The local-network permission concept does not apply to JVM desktop targets.
 * [onResult] is always called with `true` so discovery starts immediately.
 */
@Composable
actual fun RequestLocalNetworkPermission(onResult: (granted: Boolean) -> Unit) {
    LaunchedEffect(Unit) { onResult(true) }
}
