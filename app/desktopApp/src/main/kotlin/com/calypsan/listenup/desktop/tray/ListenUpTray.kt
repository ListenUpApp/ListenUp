package com.calypsan.listenup.desktop.tray

import androidx.compose.runtime.Composable

/**
 * System tray integration for ListenUp desktop.
 *
 * Currently a stub. System tray support requires careful integration
 * with the Compose Desktop Tray API which varies by platform and version.
 *
 * TODO: Implement proper system tray with:
 * - ListenUp icon
 * - Playback controls (pause/play) when playing
 * - Quick open/exit actions
 */
@Composable
fun ListenUpTray(
    onOpenWindow: () -> Unit,
    onExitApplication: () -> Unit,
) {
    // System tray disabled for initial release
    // Will be implemented in a future update with proper platform support
    //
    // The Compose Desktop Tray API requires ApplicationScope context
    // and proper icon handling that varies by platform.
}
