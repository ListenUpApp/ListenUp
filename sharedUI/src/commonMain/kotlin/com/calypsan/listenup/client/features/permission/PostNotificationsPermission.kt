package com.calypsan.listenup.client.features.permission

import androidx.compose.runtime.Composable

/**
 * Composable that requests [android.Manifest.permission.POST_NOTIFICATIONS] exactly
 * once per session and has no further effect.
 *
 * - **Android (API 33+):** Launches the system permission dialog via
 *   [androidx.activity.compose.rememberLauncherForActivityResult]. The permission
 *   was introduced with Android 13 (TIRAMISU / API 33) and is required for the
 *   playback media notification to appear. Playback itself is unaffected by a denial.
 * - **Desktop:** No dialog is shown — the permission concept does not apply to
 *   JVM desktop; this composable is a no-op.
 *
 * The composable does not block navigation or require a grant; it is fire-and-forget.
 */
@Composable
expect fun RequestPostNotificationsPermission()
