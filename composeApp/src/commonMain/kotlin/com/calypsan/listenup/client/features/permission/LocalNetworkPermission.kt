package com.calypsan.listenup.client.features.permission

import androidx.compose.runtime.Composable

/**
 * Composable that requests [android.permission.ACCESS_LOCAL_NETWORK] exactly
 * once on first composition and delivers the result to [onResult].
 *
 * - **Android (API 36+):** Launches the system permission dialog via
 *   [androidx.activity.compose.rememberLauncherForActivityResult]. The
 *   permission exists from API 36 (Android 16) and is mandatory once the app
 *   targets SDK 37 (Android 17).
 * - **Desktop:** No dialog is shown — the local-network permission concept does
 *   not exist on JVM desktop, so [onResult] is always called with `true`.
 *
 * The caller is responsible for acting on the result (e.g. starting mDNS
 * discovery or falling through to manual URL entry). The composable itself
 * has no side effects beyond the single permission request.
 */
@Composable
expect fun RequestLocalNetworkPermission(onResult: (granted: Boolean) -> Unit)
